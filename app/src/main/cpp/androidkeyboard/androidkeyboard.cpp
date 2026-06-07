/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
#include <fcitx-utils/utf8.h>
#include <fcitx-utils/charutils.h>
#include <fcitx/instance.h>
#include <fcitx/candidatelist.h>
#include <fcitx/inputpanel.h>
#include <fcitx/userinterfacemanager.h>

#include <algorithm>
#include <cctype>
#include <cstdlib>
#include <fstream>
#include <iterator>
#include <sstream>
#include <tuple>
#include <unordered_map>
#include <unordered_set>

#include "spell_public.h"
#include "../../../../../lib/fcitx5/src/main/cpp/fcitx5/src/im/keyboard/chardata.h" // dirty but works

#include "androidkeyboard.h"

namespace fcitx {

namespace {

constexpr size_t MaxPrefixWords = 4;
constexpr size_t MaxLearnedPhrasePredictions = 5000;
constexpr int LearnedPhrasePredictionIncrement = 1000;
constexpr int MaxLearnedPhrasePredictionScore = 100000;
constexpr auto LearnedPhrasePredictionFlushInterval = std::chrono::seconds(5);

std::string asciiLower(std::string value) {
    std::transform(value.begin(), value.end(), value.begin(), [](unsigned char ch) {
        return static_cast<char>(std::tolower(ch));
    });
    return value;
}

std::string trimWord(std::string line) {
    if (auto pos = line.find('#'); pos != std::string::npos) {
        line.resize(pos);
    }
    if (auto pos = line.find('\t'); pos != std::string::npos) {
        line.resize(pos);
    }
    auto begin = std::find_if(line.begin(), line.end(), [](unsigned char ch) {
        return !std::isspace(ch) && ch != ',' && ch != ';';
    });
    auto end = std::find_if(line.rbegin(), std::string::reverse_iterator(begin), [](unsigned char ch) {
        return !std::isspace(ch) && ch != ',' && ch != ';';
    }).base();
    if (begin >= end) {
        return {};
    }
    std::string word(begin, end);
    if (word.size() > 64) {
        return {};
    }
    if (!std::all_of(word.begin(), word.end(), [](unsigned char ch) {
            return std::isalpha(ch) || ch == '\'' || ch == '-';
        })) {
        return {};
    }
    return word;
}

std::string trimPhrase(std::string value) {
    auto begin = std::find_if(value.begin(), value.end(), [](unsigned char ch) {
        return !std::isspace(ch);
    });
    auto end = std::find_if(value.rbegin(), std::string::reverse_iterator(begin), [](unsigned char ch) {
        return !std::isspace(ch);
    }).base();
    if (begin >= end) {
        return {};
    }
    std::string phrase(begin, end);
    return phrase.size() <= 128 ? phrase : std::string();
}

std::vector<std::string> splitTabs(const std::string &line, size_t limit) {
    std::vector<std::string> parts;
    size_t start = 0;
    while (parts.size() + 1 < limit) {
        auto pos = line.find('\t', start);
        if (pos == std::string::npos) {
            break;
        }
        parts.emplace_back(line.substr(start, pos - start));
        start = pos + 1;
    }
    parts.emplace_back(line.substr(start));
    return parts;
}

bool isEnglishWordChar(unsigned char ch) {
    return std::isalpha(ch) || ch == '\'' || ch == '-';
}

std::vector<std::string> tokenizeEnglishWords(std::string line) {
    if (auto pos = line.find('#'); pos != std::string::npos) {
        line.resize(pos);
    }
    std::vector<std::string> words;
    std::string word;
    for (unsigned char ch: line) {
        if (isEnglishWordChar(ch)) {
            word.push_back(static_cast<char>(std::tolower(ch)));
        } else if (!word.empty()) {
            if (word.size() <= 32) {
                words.emplace_back(std::move(word));
            }
            word.clear();
        }
    }
    if (!word.empty() && word.size() <= 32) {
        words.emplace_back(std::move(word));
    }
    return words;
}

std::string joinWords(const std::vector<std::string> &words, size_t begin, size_t end) {
    std::string result;
    for (size_t i = begin; i < end; i++) {
        if (!result.empty()) {
            result.push_back(' ');
        }
        result.append(words[i]);
    }
    return result;
}

std::filesystem::path androidKeyboardDataDir() {
    const char *dataHome = std::getenv("FCITX_DATA_HOME");
    if (!dataHome) {
        return {};
    }
    return std::filesystem::path(dataHome) / "androidkeyboard";
}

void addPhrasePredictionScore(
        std::unordered_map<std::string, std::unordered_map<std::string, int>> &scores,
        const std::string &prefix,
        const std::string &next,
        int score) {
    if (prefix.empty() || next.empty()) {
        return;
    }
    auto &value = scores[prefix][next];
    value = std::min(MaxLearnedPhrasePredictionScore, value + std::max(1, score));
}

class AndroidKeyboardCandidateWord : public CandidateWord {
public:
    AndroidKeyboardCandidateWord(AndroidKeyboardEngine *engine, Text text, std::string commit)
            : CandidateWord(std::move(text)), engine_(engine),
              commit_(std::move(commit)) {}

    void select(InputContext *inputContext) const override {
        inputContext->commitString(commit_);
        engine_->recordCommittedText(inputContext, commit_);
        engine_->resetState(inputContext, true);
        inputContext->inputPanel().reset();
        if (auto *entry = engine_->instance()->inputMethodEntry(inputContext)) {
            engine_->updateCandidate(*entry, inputContext);
        } else {
            inputContext->updatePreedit();
            inputContext->updateUserInterface(UserInterfaceComponent::InputPanel);
        }
    }

    [[nodiscard]] const std::string &stringForCommit() const { return commit_; }

private:
    AndroidKeyboardEngine *engine_;
    std::string commit_;
};

} // namespace

AndroidKeyboardEngine::AndroidKeyboardEngine(Instance *instance)
        : instance_(instance) {
    instance_->inputContextManager().registerProperty("androidkeyboardState", &factory_);
    reloadConfig();
    wordHintAction_.setShortText(_("Word hint"));
    wordHintAction_.setLongText(_("Word hint"));
    wordHintAction_.setIcon("tools-check-spelling");
    wordHintAction_.setChecked(*config_.enableWordHint);
    wordHintAction_.connect<SimpleAction::Activated>([this](InputContext *ic) {
        auto enabled = !(*config_.enableWordHint);
        config_.enableWordHint.setValue(enabled);
        wordHintAction_.setChecked(enabled);
        wordHintAction_.update(ic);
    });
    instance_->userInterfaceManager().registerAction("androidkeyboard-word-hint", &wordHintAction_);
}

static inline bool isValidSym(const Key &key) {
    if (key.states()) {
        return false;
    }

    return validSyms.count(key.sym());
}

void AndroidKeyboardEngine::keyEvent(const InputMethodEntry &entry, KeyEvent &event) {
    FCITX_UNUSED(entry);

    // by pass all key release
    if (event.isRelease()) {
        return;
    }

    const auto &key = event.key();

    // and by pass all modifier
    if (key.isModifier()) {
        return;
    }

    auto *inputContext = event.inputContext();
    auto *state = inputContext->propertyFor(&factory_);
    auto &buffer = state->buffer_;

    // check if we can select candidate.
    if (auto candList = inputContext->inputPanel().candidateList()) {
        const int idx = key.keyListIndex(selectionKeys_);
        if (idx >= 0 && idx < candList->size()) {
            event.filterAndAccept();
            candList->candidate(idx).select(inputContext);
            return;
        }
    }

    const bool validSym = isValidSym(key);

    static const KeyList FCITX_HYPHEN_APOS = {Key(FcitxKey_minus), Key(FcitxKey_apostrophe)};
    // check for valid character
    if (key.isSimple() || validSym) {
        // prepend space before input next word
        if (state->prependSpace_ && buffer.empty() &&
            (key.isLAZ() || key.isUAZ() || key.isDigit())) {
            state->prependSpace_ = false;
            inputContext->commitString(" ");
        }
        if (key.isLAZ() || key.isUAZ() || validSym ||
            (!buffer.empty() && key.checkKeyList(FCITX_HYPHEN_APOS))) {
            if (updateBuffer(inputContext, event)) {
                return event.filterAndAccept();
            }
        }
    } else if (key.check(FcitxKey_BackSpace)) {
        if (buffer.backspace()) {
            event.filterAndAccept();
            if (buffer.empty()) {
                return reset(entry, event);
            }
            return updateCandidate(entry, inputContext);
        }
    } else if (key.check(FcitxKey_Delete) || key.check(FcitxKey_KP_Delete)) {
        if (buffer.del()) {
            event.filterAndAccept();
            if (buffer.empty()) {
                return reset(entry, event);
            }
            return updateCandidate(entry, inputContext);
        }
    } else if (!buffer.empty()) {
        if (key.check(FcitxKey_Home) || key.check(FcitxKey_KP_Home)) {
            buffer.setCursor(0);
            event.filterAndAccept();
            return updateCandidate(entry, inputContext);
        } else if (key.check(FcitxKey_End) || key.check(FcitxKey_KP_End)) {
            buffer.setCursor(buffer.size());
            event.filterAndAccept();
            return updateCandidate(entry, inputContext);
        } else if (key.check(FcitxKey_Left) || key.check(FcitxKey_KP_Left)) {
            auto cursor = buffer.cursor();
            if (cursor > 0) {
                buffer.setCursor(cursor - 1);
                event.filterAndAccept();
                return updateCandidate(entry, inputContext);
            }
        } else if (key.check(FcitxKey_Right) || key.check(FcitxKey_KP_Right)) {
            auto cursor = buffer.cursor();
            if (cursor < buffer.size()) {
                buffer.setCursor(buffer.cursor() + 1);
                event.filterAndAccept();
                return updateCandidate(entry, inputContext);
            }
        }
    }

    // if we reach here, just commit and discard buffer.
    commitBuffer(inputContext);
    if (state->prependSpace_) {
        state->prependSpace_ = false;
    }
}

std::vector<InputMethodEntry> AndroidKeyboardEngine::listInputMethods() {
    std::vector<InputMethodEntry> result;
    result.emplace_back(std::move(
            InputMethodEntry("keyboard-us", _("English"), "en", "androidkeyboard")
                    .setLabel("En")
                    .setIcon("input-keyboard")
                    .setConfigurable(true)));
    return result;
}

void AndroidKeyboardEngine::reloadConfig() {
    readAsIni(config_, ConfPath);
    userWordsLastModified_ = {};
    reloadUserWordsIfNeeded();
    selectionKeys_.clear();
    const std::array<KeySym, 10> syms{
            FcitxKey_1, FcitxKey_2, FcitxKey_3, FcitxKey_4, FcitxKey_5,
            FcitxKey_6, FcitxKey_7, FcitxKey_8, FcitxKey_9, FcitxKey_0,
    };

    KeyStates states;
    switch (*config_.chooseModifier) {
        case ChooseModifier::Alt:
            states = KeyState::Alt;
            break;
        case ChooseModifier::Control:
            states = KeyState::Ctrl;
            break;
        case ChooseModifier::Super:
            states = KeyState::Super;
            break;
        case ChooseModifier::NoModifier:
            break;
    }

    for (auto sym: syms) {
        selectionKeys_.emplace_back(sym, states);
    }
}

void AndroidKeyboardEngine::save() {
    flushLearnedPhrasePredictions(true);
    safeSaveAsIni(config_, ConfPath);
}

void AndroidKeyboardEngine::setConfig(const RawConfig &config) {
    config_.load(config, true);
    safeSaveAsIni(config_, ConfPath);
    reloadConfig();
}

void AndroidKeyboardEngine::activate(const InputMethodEntry &entry, InputContextEvent &event) {
    FCITX_UNUSED(entry);
    auto *inputContext = event.inputContext();
    wordHintAction_.setChecked(*config_.enableWordHint);
    wordHintAction_.update(inputContext);
    inputContext->statusArea().addAction(StatusGroup::InputMethod, &wordHintAction_);
}

void AndroidKeyboardEngine::deactivate(const InputMethodEntry &entry, InputContextEvent &event) {
    auto *inputContext = event.inputContext();
    // Android would commit composing text when finishing input (we simulate as focus out/in),
    // but not so when switching input method in fcitx
    if (event.type() == EventType::InputContextSwitchInputMethod && inputContext->hasFocus()) {
        commitBuffer(inputContext);
    }
    flushLearnedPhrasePredictions(true);
    reset(entry, event);
}

void AndroidKeyboardEngine::reset(const InputMethodEntry &entry, InputContextEvent &event) {
    FCITX_UNUSED(entry);
    auto *inputContext = event.inputContext();
    resetState(inputContext);
    inputContext->propertyFor(&factory_)->contextWords_.clear();
    inputContext->inputPanel().reset();
    inputContext->updatePreedit();
    inputContext->updateUserInterface(UserInterfaceComponent::InputPanel);
}

void AndroidKeyboardEngine::resetState(InputContext *inputContext, bool fromCandidate) {
    auto *state = inputContext->propertyFor(&factory_);
    state->reset();
    if (fromCandidate) {
        // TODO set prependSpace_ to false when cursor moves; maybe it's time to implement SurroundingText
        state->prependSpace_ = *config_.insertSpace;
    }
}

void AndroidKeyboardEngine::recordCommittedText(InputContext *inputContext, const std::string &text) {
    auto words = tokenizeEnglishWords(text);
    if (words.empty()) {
        return;
    }
    learnPhrasePrediction(inputContext, words);
    auto *state = inputContext->propertyFor(&factory_);
    state->contextWords_.insert(state->contextWords_.end(), words.begin(), words.end());
    constexpr size_t MaxContextWords = 4;
    if (state->contextWords_.size() > MaxContextWords) {
        state->contextWords_.erase(
                state->contextWords_.begin(),
                state->contextWords_.begin() + static_cast<std::ptrdiff_t>(
                        state->contextWords_.size() - MaxContextWords));
    }
}

void AndroidKeyboardEngine::learnPhrasePrediction(
        InputContext *inputContext,
        const std::vector<std::string> &words) {
    const auto base = androidKeyboardDataDir();
    if (base.empty()) {
        return;
    }
    auto *state = inputContext->propertyFor(&factory_);
    std::vector<std::string> combined = state->contextWords_;
    const auto firstNewWord = combined.size();
    combined.insert(combined.end(), words.begin(), words.end());
    if (combined.size() < 2) {
        return;
    }

    const auto learnBegin = firstNewWord == 0 ? 1 : firstNewWord;
    for (size_t nextIndex = learnBegin; nextIndex < combined.size(); nextIndex++) {
        const auto maxLen = std::min(MaxPrefixWords, nextIndex);
        for (size_t len = 1; len <= maxLen; len++) {
            const auto prefix = joinWords(combined, nextIndex - len, nextIndex);
            const auto &next = combined[nextIndex];
            addPhrasePredictionScore(
                    learnedPhrasePredictionScores_,
                    prefix,
                    next,
                    LearnedPhrasePredictionIncrement);
            addPhrasePredictionToCache(prefix, next, LearnedPhrasePredictionIncrement);
        }
    }

    learnedPhrasePredictionsDirty_ = true;
    learnedPhrasePredictionsLastChanged_ = std::chrono::steady_clock::now();
    flushLearnedPhrasePredictions();
}

void AndroidKeyboardEngine::flushLearnedPhrasePredictions(bool force) {
    if (!learnedPhrasePredictionsDirty_) {
        return;
    }
    if (!force && std::chrono::steady_clock::now() - learnedPhrasePredictionsLastChanged_ <
                          LearnedPhrasePredictionFlushInterval) {
        return;
    }
    const auto base = androidKeyboardDataDir();
    if (base.empty()) {
        return;
    }

    std::vector<std::tuple<std::string, std::string, int>> flattened;
    for (const auto &[prefix, nextScores]: learnedPhrasePredictionScores_) {
        for (const auto &[next, score]: nextScores) {
            flattened.emplace_back(prefix, next, score);
        }
    }
    std::stable_sort(flattened.begin(), flattened.end(), [](const auto &lhs, const auto &rhs) {
        if (std::get<2>(lhs) != std::get<2>(rhs)) {
            return std::get<2>(lhs) > std::get<2>(rhs);
        }
        if (std::get<0>(lhs) != std::get<0>(rhs)) {
            return std::get<0>(lhs) < std::get<0>(rhs);
        }
        return std::get<1>(lhs) < std::get<1>(rhs);
    });
    if (flattened.size() > MaxLearnedPhrasePredictions) {
        flattened.resize(MaxLearnedPhrasePredictions);
    }

    learnedPhrasePredictionScores_.clear();
    for (const auto &[prefix, next, score]: flattened) {
        learnedPhrasePredictionScores_[prefix][next] = score;
    }

    std::error_code ec;
    std::filesystem::create_directories(base, ec);
    const auto learnedFile = base / "learned_phrase_predictions.txt";
    std::ofstream stream(learnedFile, std::ios::trunc);
    for (const auto &[prefix, next, score]: flattened) {
        stream << prefix << '\t' << next << '\t' << score << '\n';
    }
    stream.close();
    if (const auto modified = std::filesystem::last_write_time(learnedFile, ec); !ec) {
        userWordsLastModified_ = std::max(userWordsLastModified_, modified);
    }
    learnedPhrasePredictionsDirty_ = false;
}

void AndroidKeyboardEngine::addPhrasePredictionToCache(
        const std::string &prefix,
        const std::string &next,
        int score) {
    if (prefix.empty() || next.empty()) {
        return;
    }
    auto &items = phrasePredictions_[prefix];
    auto iter = std::find_if(items.begin(), items.end(), [&next](const auto &item) {
        return item.next == next;
    });
    if (iter != items.end()) {
        iter->score = std::min(
                MaxLearnedPhrasePredictionScore,
                iter->score + std::max(1, score));
    } else {
        items.push_back({next, std::max(1, score)});
    }
    std::stable_sort(items.begin(), items.end(), [](const auto &lhs, const auto &rhs) {
        if (lhs.score != rhs.score) {
            return lhs.score > rhs.score;
        }
        return lhs.next < rhs.next;
    });
    if (items.size() > static_cast<size_t>(SpellCandidateSize)) {
        items.resize(SpellCandidateSize);
    }
}

void AndroidKeyboardEngine::updateCandidate(const InputMethodEntry &entry, InputContext *inputContext) {
    inputContext->inputPanel().reset();
    auto *state = inputContext->propertyFor(&factory_);
    const auto userInput = state->buffer_.userInput();
    std::vector<std::pair<std::string, std::string>> spellResults;
    std::vector<std::pair<std::string, std::string>> customResults;
    std::vector<std::pair<std::string, std::string>> phraseResults;
    std::vector<std::pair<std::string, std::string>> wordResults;
    if (spell()) {
        spellResults = spell()->call<ISpell::hintForDisplay>(entry.languageCode(),
                                                             SpellProvider::Default,
                                                             userInput,
                                                             SpellCandidateSize);
    }
    if (*config_.enableUserWordHint && entry.languageCode() == "en") {
        reloadUserWordsIfNeeded();
        customResults = customPhraseHints(userInput);
        phraseResults = phrasePredictionHints(inputContext, userInput);
        wordResults = userWordHints(userInput);
    }
    auto candidateList = std::make_unique<CommonCandidateList>();
    std::unordered_set<std::string> commits;
    auto appendCandidate = [&](std::pair<std::string, std::string> result) {
        if (commits.insert(result.second).second) {
            candidateList->append<AndroidKeyboardCandidateWord>(this, Text(result.first), result.second);
        }
    };

    for (auto &result: customResults) {
        appendCandidate(std::move(result));
    }

    const auto directCommit = [&] {
        if (commits.contains(userInput)) {
            return true;
        }
        return std::any_of(spellResults.begin(), spellResults.end(), [&userInput](const auto &result) {
            return result.second == userInput;
        });
    }();
    if (!userInput.empty() && !directCommit) {
        // TODO: comply with fcitx5 spell module's delim " _-,./?!%"
        // it's fine in androidkeyboard because only "-" won't commit buffer
        const auto segments = stringutils::split(userInput, "-");
        const auto label = segments.size() > 1 ? segments.back() : userInput;
        appendCandidate({label, userInput});
    }

    int visibleCandidateCount = static_cast<int>(candidateList->totalSize());
    auto appendPhraseAndWordResults = [&] {
        for (auto &result: phraseResults) {
            appendCandidate(std::move(result));
        }
        phraseResults.clear();
        for (auto &result: wordResults) {
            appendCandidate(std::move(result));
        }
        wordResults.clear();
    };
    for (auto &result: spellResults) {
        if (visibleCandidateCount >= 2) {
            appendPhraseAndWordResults();
        }
        const auto before = candidateList->totalSize();
        appendCandidate(std::move(result));
        if (candidateList->totalSize() != before) {
            visibleCandidateCount++;
        }
    }
    appendPhraseAndWordResults();
    candidateList->setPageSize(*config_.pageSize);
    candidateList->setSelectionKey(selectionKeys_);
    candidateList->setCursorIncludeUnselected(true);
    inputContext->inputPanel().setCandidateList(std::move(candidateList));

    updateUI(inputContext);
}

void AndroidKeyboardEngine::updateUI(InputContext *inputContext) {
    auto [text, cursor] = preeditWithCursor(inputContext);
    if (inputContext->capabilityFlags().test(CapabilityFlag::Preedit)) {
        Text clientPreedit(text, TextFormatFlag::Underline);
        clientPreedit.setCursor(static_cast<int>(cursor));
        inputContext->inputPanel().setClientPreedit(clientPreedit);
        inputContext->updatePreedit();
    } else {
        Text preedit(text);
        preedit.setCursor(static_cast<int>(cursor));
        inputContext->inputPanel().setPreedit(preedit);
    }
    inputContext->updateUserInterface(UserInterfaceComponent::InputPanel);
}

bool AndroidKeyboardEngine::updateBuffer(InputContext *inputContext, const KeyEvent& event) {
    auto *entry = instance_->inputMethodEntry(inputContext);
    if (!entry) {
        return false;
    }

    auto *state = inputContext->propertyFor(&factory_);
    // word hint is disabled, input is password, or language not supported
    if (!*config_.enableWordHint ||
        (!*config_.hintOnPhysicalKeyboard && !event.isVirtual()) ||
        (*config_.editorControlledWordHint && inputContext->capabilityFlags().test(CapabilityFlag::NoSpellCheck)) ||
        inputContext->capabilityFlags().test(CapabilityFlag::Password) ||
        !supportHint(entry->languageCode())) {
        return false;
    }

    auto &buffer = state->buffer_;
    auto [preedit, cursor] = preeditWithCursor(inputContext);
    if (preedit != buffer.userInput()) {
        buffer.clear();
        buffer.type(preedit);
    }

    buffer.type(Key::keySymToUTF8(event.key().sym()));

    if (buffer.size() >= MaxBufferSize) {
        commitBuffer(inputContext);
        return true;
    }

    updateCandidate(*entry, inputContext);
    return true;
}

void AndroidKeyboardEngine::commitBuffer(InputContext *inputContext) {
    auto [preedit, cursor] = preeditWithCursor(inputContext);
    if (preedit.empty()) {
        return;
    }
    auto characterCount = utf8::length(preedit, 0, cursor);
    if (inputContext->capabilityFlags().test(CapabilityFlag::CommitStringWithCursor)) {
        inputContext->commitStringWithCursor(preedit, characterCount);
    } else {
        inputContext->commitString(preedit);
    }
    recordCommittedText(inputContext, preedit);
    resetState(inputContext);
    inputContext->inputPanel().reset();
    inputContext->updatePreedit();
    inputContext->updateUserInterface(UserInterfaceComponent::InputPanel);
}

bool AndroidKeyboardEngine::supportHint(const std::string &language) {
    const bool hasSpell = spell() && spell()->call<ISpell::checkDict>(language);
    return hasSpell || (language == "en" && *config_.enableUserWordHint);
}

std::pair<std::string, size_t> AndroidKeyboardEngine::preeditWithCursor(InputContext *inputContext) {
    auto *state = inputContext->propertyFor(&factory_);
    return {state->buffer_.userInput(), state->buffer_.cursorByChar()};
}

void AndroidKeyboardEngine::reloadUserWordsIfNeeded() {
    const char *dataHome = std::getenv("FCITX_DATA_HOME");
    if (!dataHome) {
        return;
    }

    namespace fs = std::filesystem;
    const fs::path base = fs::path(dataHome) / "androidkeyboard";
    const fs::path userWordsFile = base / "user_words.txt";
    const fs::path customPhrasesFile = base / "custom_phrases.txt";
    const fs::path customPhrasePredictionsFile = base / "custom_phrase_predictions.txt";
    const fs::path learnedPhrasePredictionsFile = base / "learned_phrase_predictions.txt";
    const fs::path phrasePredictionsFile = base / "phrase_predictions.txt";
    const fs::path dictionariesDir = base / "dictionaries";
    const fs::path phraseBooksDir = base / "phrasebooks";

    fs::file_time_type newest{};
    auto considerModified = [&newest](const fs::path &path) {
        std::error_code ec;
        if (fs::exists(path, ec) && fs::is_regular_file(path, ec)) {
            newest = std::max(newest, fs::last_write_time(path, ec));
        }
    };
    considerModified(userWordsFile);
    considerModified(customPhrasesFile);
    considerModified(customPhrasePredictionsFile);
    considerModified(learnedPhrasePredictionsFile);
    considerModified(phrasePredictionsFile);
    std::error_code ec;
    if (fs::exists(dictionariesDir, ec) && fs::is_directory(dictionariesDir, ec)) {
        for (const auto &entry: fs::directory_iterator(dictionariesDir, ec)) {
            if (!entry.is_regular_file(ec) || entry.path().extension() != ".txt") {
                continue;
            }
            considerModified(entry.path());
        }
    }
    if (fs::exists(phraseBooksDir, ec) && fs::is_directory(phraseBooksDir, ec)) {
        for (const auto &entry: fs::directory_iterator(phraseBooksDir, ec)) {
            if (!entry.is_regular_file(ec) || entry.path().extension() != ".txt") {
                continue;
            }
            considerModified(entry.path());
        }
    }
    std::vector<fs::path> systemPhraseFiles;
    if (const char *dataDirs = std::getenv("XDG_DATA_DIRS")) {
        std::stringstream stream(dataDirs);
        std::string dir;
        while (std::getline(stream, dir, ':')) {
            if (dir.empty()) {
                continue;
            }
            auto path = fs::path(dir) / "fcitx5" / "androidkeyboard" / "phrase_predictions.txt";
            considerModified(path);
            systemPhraseFiles.emplace_back(std::move(path));
        }
    }
    if (newest == userWordsLastModified_) {
        return;
    }
    if (learnedPhrasePredictionsDirty_) {
        flushLearnedPhrasePredictions(true);
        considerModified(learnedPhrasePredictionsFile);
    }

    std::vector<std::string> words;
    std::vector<CustomPhrase> phrases;
    std::unordered_map<std::string, std::unordered_map<std::string, int>> predictionScores;
    learnedPhrasePredictionScores_.clear();
    learnedPhrasePredictionsDirty_ = false;
    std::unordered_set<std::string> seen;
    auto readWords = [&words, &seen](const fs::path &path) {
        std::ifstream stream(path);
        std::string line;
        while (std::getline(stream, line)) {
            auto word = trimWord(std::move(line));
            if (word.empty()) {
                continue;
            }
            if (seen.insert(asciiLower(word)).second) {
                words.emplace_back(std::move(word));
            }
        }
    };
    auto addPhrasePrediction = [&predictionScores](
            const std::vector<std::string> &phraseWords,
            int score) {
        if (phraseWords.size() < 2 || phraseWords.size() > 12) {
            return;
        }
        for (size_t begin = 0; begin + 1 < phraseWords.size(); begin++) {
            const auto maxLen = std::min(MaxPrefixWords, phraseWords.size() - begin - 1);
            for (size_t len = 1; len <= maxLen; len++) {
                const auto next = phraseWords[begin + len];
                predictionScores[joinWords(phraseWords, begin, begin + len)][next] += score;
            }
        }
    };
    auto readPhrasePredictions = [&addPhrasePrediction, &predictionScores](const fs::path &path, int baseScore) {
        std::ifstream stream(path);
        if (!stream) {
            return;
        }
        std::string line;
        while (std::getline(stream, line)) {
            auto parts = splitTabs(line, 3);
            if (parts.size() >= 2 && line.find('\t') != std::string::npos) {
                auto prefixWords = tokenizeEnglishWords(parts[0]);
                auto nextWords = tokenizeEnglishWords(parts[1]);
                if (!prefixWords.empty() && !nextWords.empty()) {
                    int score = baseScore;
                    if (parts.size() >= 3) {
                        try {
                            score = std::max(1, std::stoi(parts[2]));
                        } catch (...) {
                        }
                    }
                    predictionScores[joinWords(prefixWords, 0, prefixWords.size())][nextWords.front()] += score;
                } else if (!prefixWords.empty()) {
                    try {
                        addPhrasePrediction(prefixWords, std::max(1, std::stoi(parts[1])));
                    } catch (...) {
                    }
                }
                continue;
            }
            addPhrasePrediction(tokenizeEnglishWords(std::move(line)), baseScore);
        }
    };
    auto readLearnedPhrasePredictions = [this, &predictionScores](const fs::path &path) {
        std::ifstream stream(path);
        if (!stream) {
            return;
        }
        std::string line;
        while (std::getline(stream, line)) {
            auto parts = splitTabs(line, 3);
            if (parts.size() < 3) {
                continue;
            }
            const auto prefixWords = tokenizeEnglishWords(parts[0]);
            const auto nextWords = tokenizeEnglishWords(parts[1]);
            if (prefixWords.empty() || nextWords.empty()) {
                continue;
            }
            int score = 1;
            try {
                score = std::max(1, std::stoi(parts[2]));
            } catch (...) {
            }
            const auto prefix = joinWords(prefixWords, 0, prefixWords.size());
            const auto &next = nextWords.front();
            const auto normalizedScore = std::min(MaxLearnedPhrasePredictionScore, score);
            learnedPhrasePredictionScores_[prefix][next] = normalizedScore;
            predictionScores[prefix][next] += normalizedScore;
        }
    };
    if (fs::exists(userWordsFile, ec)) {
        readWords(userWordsFile);
    }
    if (fs::exists(customPhrasesFile, ec)) {
        std::ifstream stream(customPhrasesFile);
        std::string line;
        while (std::getline(stream, line)) {
            auto parts = splitTabs(line, 4);
            if (parts.size() < 4 || parts[0] == "0") {
                continue;
            }
            auto key = asciiLower(trimWord(parts[1]));
            auto phrase = trimPhrase(parts[3]);
            if (key.empty() || phrase.empty()) {
                continue;
            }
            int order = 1;
            try {
                order = std::stoi(parts[2]);
            } catch (...) {
            }
            phrases.push_back({std::move(key), order, std::move(phrase)});
        }
        std::stable_sort(phrases.begin(), phrases.end(), [](const auto &lhs, const auto &rhs) {
            return lhs.order < rhs.order;
        });
    }
    if (fs::exists(dictionariesDir, ec) && fs::is_directory(dictionariesDir, ec)) {
        std::vector<fs::path> dictionaries;
        for (const auto &entry: fs::directory_iterator(dictionariesDir, ec)) {
            if (entry.is_regular_file(ec) && entry.path().extension() == ".txt") {
                dictionaries.emplace_back(entry.path());
            }
        }
        std::sort(dictionaries.begin(), dictionaries.end());
        for (const auto &path: dictionaries) {
            readWords(path);
        }
    }
    for (const auto &path: systemPhraseFiles) {
        readPhrasePredictions(path, 1);
    }
    if (fs::exists(phrasePredictionsFile, ec)) {
        readPhrasePredictions(phrasePredictionsFile, 5);
    }
    if (fs::exists(customPhrasePredictionsFile, ec)) {
        readPhrasePredictions(customPhrasePredictionsFile, 5);
    }
    if (fs::exists(learnedPhrasePredictionsFile, ec)) {
        readLearnedPhrasePredictions(learnedPhrasePredictionsFile);
    }
    if (fs::exists(phraseBooksDir, ec) && fs::is_directory(phraseBooksDir, ec)) {
        std::vector<fs::path> phraseBooks;
        for (const auto &entry: fs::directory_iterator(phraseBooksDir, ec)) {
            if (entry.is_regular_file(ec) && entry.path().extension() == ".txt") {
                phraseBooks.emplace_back(entry.path());
            }
        }
        std::sort(phraseBooks.begin(), phraseBooks.end());
        for (const auto &path: phraseBooks) {
            readPhrasePredictions(path, 5);
        }
    }
    std::unordered_map<std::string, std::vector<PhrasePrediction>> predictions;
    for (auto &[prefix, nextScores]: predictionScores) {
        auto &items = predictions[prefix];
        items.reserve(nextScores.size());
        for (auto &[next, score]: nextScores) {
            items.push_back({std::move(next), score});
        }
        std::stable_sort(items.begin(), items.end(), [](const auto &lhs, const auto &rhs) {
            if (lhs.score != rhs.score) {
                return lhs.score > rhs.score;
            }
            return lhs.next < rhs.next;
        });
        if (items.size() > static_cast<size_t>(SpellCandidateSize)) {
            items.resize(SpellCandidateSize);
        }
    }
    userWords_ = std::move(words);
    customPhrases_ = std::move(phrases);
    phrasePredictions_ = std::move(predictions);
    userWordsLastModified_ = newest;
}

std::vector<std::pair<std::string, std::string>>
AndroidKeyboardEngine::userWordHints(const std::string &input) {
    std::vector<std::pair<std::string, std::string>> result;
    if (input.empty()) {
        return result;
    }
    const auto lowerInput = asciiLower(input);
    for (const auto &word: userWords_) {
        const auto lowerWord = asciiLower(word);
        if (lowerWord.size() < lowerInput.size() ||
            lowerWord.compare(0, lowerInput.size(), lowerInput) != 0) {
            continue;
        }
        result.emplace_back(word, word);
        if (result.size() >= static_cast<size_t>(SpellCandidateSize)) {
            break;
        }
    }
    return result;
}

std::vector<std::pair<std::string, std::string>>
AndroidKeyboardEngine::customPhraseHints(const std::string &input) {
    std::vector<std::pair<std::string, std::string>> result;
    if (input.empty()) {
        return result;
    }
    const auto lowerInput = asciiLower(input);
    for (const auto &phrase: customPhrases_) {
        if (phrase.key != lowerInput) {
            continue;
        }
        result.emplace_back(phrase.phrase, phrase.phrase);
        if (result.size() >= static_cast<size_t>(SpellCandidateSize)) {
            break;
        }
    }
    return result;
}

std::vector<std::pair<std::string, std::string>>
AndroidKeyboardEngine::phrasePredictionHints(InputContext *inputContext, const std::string &input) {
    std::vector<std::pair<std::string, std::string>> result;
    if (phrasePredictions_.empty()) {
        return result;
    }

    const auto inputWords = tokenizeEnglishWords(input);
    auto *state = inputContext->propertyFor(&factory_);
    std::vector<std::string> queryWords;
    bool hasInput = !inputWords.empty();
    if (hasInput) {
        queryWords = inputWords;
    } else {
        queryWords = state->contextWords_;
    }
    if (queryWords.empty()) {
        return result;
    }

    const size_t maxLen = std::min<size_t>(4, queryWords.size());
    for (size_t len = maxLen; len >= 1; len--) {
        const auto prefix = joinWords(queryWords, queryWords.size() - len, queryWords.size());
        const auto iter = phrasePredictions_.find(prefix);
        if (iter == phrasePredictions_.end()) {
            if (len == 1) {
                break;
            }
            continue;
        }
        const auto limit = std::min<size_t>(
                static_cast<size_t>(*config_.phrasePredictionSize),
                iter->second.size());
        for (size_t i = 0; i < limit; i++) {
            const auto &prediction = iter->second[i];
            if (hasInput) {
                result.emplace_back(prediction.next, input + " " + prediction.next);
            } else {
                result.emplace_back(prediction.next, " " + prediction.next);
            }
        }
        break;
    }
    return result;
}

void AndroidKeyboardEngine::invokeActionImpl(const InputMethodEntry &entry, InvokeActionEvent &event) {
    const int cursor = event.cursor();
    auto inputContext = event.inputContext();
    auto *state = inputContext->propertyFor(&factory_);
    if (event.action() != InvokeActionEvent::Action::LeftClick
        || cursor < 0
        || static_cast<size_t>(cursor) > state->buffer_.size()) {
        return InputMethodEngineV3::invokeActionImpl(entry, event);
    }
    event.filter();
    state->buffer_.setCursor(static_cast<size_t>(cursor));
    updateUI(inputContext);
}

} // namespace fcitx

FCITX_ADDON_FACTORY(fcitx::AndroidKeyboardEngineFactory)
