# 專案紀錄

## 分支與遠端

- `origin`：EZ fork，`https://github.com/ezn24/fcitx5-android`。
- `upstream`：官方專案，`https://github.com/fcitx5-android/fcitx5-android.git`。
- `master`：官方基線，追蹤 `upstream/master`；保持與官方一致，不加入 EZ 客製修改。
- `main`：EZ 發行與開發分支，包含官方更新、原 `fx` 功能及 EZ 客製功能。
- `origin/fx`：已歸檔，只作歷史參考，不再作為更新來源。

## 「更新」的含義

使用者說「更新」時：

1. 從 `upstream/master` 抓取最新官方更新。
2. 將本地 `master` 快轉到 `upstream/master`，並同步 `origin/master`。
3. 將 `upstream/master` 合入 `main`，保留 EZ 現有功能與品牌設定。
4. 更新需要的子模組，解決衝突後執行 Fx Debug 組裝驗證。
5. 不再從 `origin/fx` 拉取或合併更新。
6. 未明確要求 `push` 時，不推送 `main` 的新提交。

## 需要保留的專案特徵

- 應用名稱與發行身份使用 EZ，不跟隨上游改成 Fx。
- 使用目前固定的舊版 EZ 應用程式啟動圖標，不隨上游圖標更新。
- 保留原 `fx` 已帶入 `main` 的鍵盤配置、數字鍵盤、巨集、自訂主題與圖示、Kawaii Bar、語音輸入、剪貼簿同步、文字編輯器及相關效能修正。
- 保留英文詞語聯想、自訂聯想與使用頻率學習機制。
- 保留拼音管理詞典同步修正。
- 保留僅在拼音輸入法顯示的簡／繁切換工具列按鈕，狀態文字需隨目前模式切換。
- 保留提供給 Samsung／One UI 使用者的系統鍵盤音效選項與按鍵類型音效分類。
- 一般功能設計需避免每次按鍵讀檔、同步磁碟寫入或不必要的全量重建，以控制延遲、記憶體與耗電。

## Emoji 表維護

- Emoji 面板的分類及順序參考 `PoomSmart/EmojiLibrary`，使用固定來源提交，並轉換為 Unicode Emoji 17.0 的標準序列。
- 更新清單：`python tools/update_emoji_picker.py`；核對清單：`python tools/update_emoji_picker.py --check`。只有維護腳本需要連網，鍵盤執行時使用內建 Kotlin 陣列。
- 來源授權記錄於 `app/licenses/libraries/emojilibrary.json`，完整 MIT 聲明隨應用打包於 `app/src/main/assets/licenses/EmojiLibrary.txt`。
- 這是參考來源的 iOS 排序，不宣稱已逐項核對最新 iOS；保留目前分頁、最近使用、膚色選擇及系統字型渲染。
- 長按只顯示目前 emoji 的膚色，不跨性別擴展。兔耳舞者、摔跤者與芭蕾舞者補充 Emoji 17.0 的標準序列識別；仍依字型實際顯示能力過濾。

## Emoji 搜尋與拼音資料

- Emoji 分類列的搜尋按鈕開啟鍵盤內的搜尋欄；支援繁中、簡中、英文名稱／關鍵字，以及既有和使用者自訂拼音對應。搜尋結果可長按選膚色，選中後只將 emoji 送入原應用。
- 搜尋時保留完整鍵盤高度，另加搜尋欄 40dp 與結果列 48dp；退出搜尋即恢復，不改寫使用者的高度設定。初始化期間按鍵保持可見，但在搜尋輸入接管完成前攔截觸控。
- 搜尋頁直接掛載既有 KeyboardWindow 的鍵盤視圖，不重建另一套按鍵；地球鍵、長按空格切換輸入法、數字／自訂配置等沿用原有操作和使用者設定。退出搜尋會歸還視圖並恢復回車圖示。搜尋輸入不繼承原應用網址／密碼欄的限制，保留中文組字能力並停用個人化學習。
- CLDR 48 資料固定在提交 `acd6d88ae493633240e19a87a721076a8a75c310`；執行時離線，索引首次搜尋才背景載入並快取，查詢防抖 100ms。
- 準備維護依賴：`python -m pip install --target tools/.emoji-deps -r tools/requirements-emoji.txt`。
- 生成／核對：`python tools/update_emoji_search.py`／`python tools/update_emoji_search.py --check`。下載快取及生成用依賴均不提交，也不打包進 APK。
- 預設拼音 emoji 詞典保留舊行及其權重，只追加 CLDR 精確中文名稱的缺失對應，新增權重為 `0.1`；不把全部寬泛關鍵字加入拼音候選。
- 已編輯的使用者字典仍整份優先於預設字典，不自動插入新增預設詞條；搜尋重新開啟時讀取目前的自訂對應，並取代舊預設拼音別名。
- CLDR 完整授權隨應用打包於 `app/src/main/assets/licenses/CLDR.txt`。

## 建置

- 主要本機驗證命令：`./gradlew.bat :app:assembleFxDebug`
- 發行簽章資料不得寫入版本庫或本文件。
- CI 的個人化準備腳本只驗證 `fcitx5`、`prebuilt`、`fcitx5-rime` 的鎖定提交，不抓取 FX 分支、不重設源碼或臨時套用 FX 補丁。官方源碼與預建依賴必須配對，避免缺失 `nlohmann_json`，或 FX Rime 專用 API 與官方 librime 不相容。
