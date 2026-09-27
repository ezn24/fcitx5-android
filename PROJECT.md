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

## 驗證

- 主要本機驗證命令：`./gradlew.bat :app:assembleFxDebug`
- 發行簽章資料不得寫入版本庫或本文件。
