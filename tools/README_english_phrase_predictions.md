# English Phrase Prediction Data

`generate_english_phrase_predictions.py` builds the English next-word table used by
`androidkeyboard`.

The app reads:

```text
app/src/main/assets/usr/share/fcitx5/androidkeyboard/phrase_predictions.txt
```

Each row is:

```text
prefix words<TAB>next<TAB>score
```

Example:

```text
once upon	a	100
upon a	time	90
```

Generate a small test file from the bundled seed:

```powershell
python tools/generate_english_phrase_predictions.py `
  --extra-phrases tools/english_phrase_seed.txt `
  --output build/generated/phrase_predictions.txt
```

Generate from Tatoeba:

```powershell
python tools/generate_english_phrase_predictions.py `
  --download-tatoeba `
  --extra-phrases tools/english_phrase_seed.txt `
  --output app/src/main/assets/usr/share/fcitx5/androidkeyboard/phrase_predictions.txt
```

Add selected Google Books Ngram 2020 shards for scoring:

```powershell
python tools/generate_english_phrase_predictions.py `
  --download-tatoeba `
  --extra-phrases tools/english_phrase_seed.txt `
  --google-2020-shards 2:0-30,3:0-80 `
  --output app/src/main/assets/usr/share/fcitx5/androidkeyboard/phrase_predictions.txt
```

Do not request all Google shards casually. The 2020 English corpus is split into many
large files, especially for 3-grams and above.

The generated app asset should keep more entries than the UI normally shows. Runtime
display is controlled by the `PhrasePredictionSize` input-method setting, while the
generator's `--top-per-prefix` decides the maximum available choices per prefix.
