# 浮動鍵盤 Intent 接口

先在 EZ「虛擬鍵盤 → 按鍵行為」開啟「允許其他應用程式控制浮動鍵盤」。
預設關閉；開啟後，其他 App 可以透過以下廣播改變浮動模式，請只在需要時啟用。

## 協議

- 類型：Broadcast，不是啟動 Activity 或 Service。
- Action：`<EZ 套件名>.action.SET_FLOATING_KEYBOARD`。
- Extra：`enabled`，必須是 Boolean；`true` 開啟，`false` 關閉。
- 接收器類別：`org.fcitx.fcitx5.android.input.FloatingKeyboardReceiver`。
- 相同值重複傳送不會反向切換。不接受字串 `"true"`、數字或缺少 extra 的請求。
- 顯示中的鍵盤立即切換；沒有顯示時保存模式，下一次顯示套用。
- 不會主動彈出鍵盤、啟動錄音或取得輸入文字。
- 開啟浮動模式會退出單手模式；關閉回到正常模式，不自動還原先前單手模式。
- 關閉允許開關會阻擋後續指令，但不改變目前模式。
- 僅設定模式，不偵測 CarPlay 連線，也不提供斷線自動恢復；發送端需在斷線時傳送關閉指令。

## ADB 範例

正式版：

```powershell
adb shell am broadcast -n org.fcitx.fcitx5.android.ez/org.fcitx.fcitx5.android.input.FloatingKeyboardReceiver -a org.fcitx.fcitx5.android.ez.action.SET_FLOATING_KEYBOARD --ez enabled true
adb shell am broadcast -n org.fcitx.fcitx5.android.ez/org.fcitx.fcitx5.android.input.FloatingKeyboardReceiver -a org.fcitx.fcitx5.android.ez.action.SET_FLOATING_KEYBOARD --ez enabled false
```

Debug 版把目標套件與 Action 中的 `org.fcitx.fcitx5.android.ez` 改成
`org.fcitx.fcitx5.android.ez.debug`，接收器類別不變。

## CarDeck 發送範例

在實際連線成功／斷線事件呼叫，不能只用 CarDeck 的服務啟動代表連線成功。
CarDeck 的專案本次沒有修改。

```kotlin
fun setEzFloatingKeyboard(context: Context, enabled: Boolean) {
    val targetPackage = "org.fcitx.fcitx5.android.ez"
    context.sendBroadcast(
        Intent("$targetPackage.action.SET_FLOATING_KEYBOARD").apply {
            component = ComponentName(
                targetPackage,
                "org.fcitx.fcitx5.android.input.FloatingKeyboardReceiver"
            )
            putExtra("enabled", enabled)
        }
    )
}
```

明確指定 component 可在鍵盤尚未建立時送達接收器；EZ 必須已完成首次啟動且未被強制停止。
強制停止、移除 App 或發送端異常退出時，不保證收到廣播，不能將此接口當成連線存活保證。
