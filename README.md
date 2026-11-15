# 魂壓 / Soul Pressure — Fabric 1.20.1

這是 **原始碼 ZIP**，不是可以直接放進 `mods` 的 JAR。GitHub Actions 編譯後會產生可安裝的 `soul-pressure-1.0.0.jar`。

## 操作

| 按鍵 | 模式 | 效果 |
| --- | --- | --- |
| **R** | 範圍魂壓 | 以施放瞬間的玩家座標為圓心，水平半徑 10 格、上下各 10 格的固定圓柱區域；再按一次關閉 |
| **V** | 單體魂壓 | 用準星指定可看見的 20 格內生物；定在當下位置；再按一次關閉 |
| **左鍵** | 一擊擊殺 | 對正在被魂壓固定的生物普通攻擊，即可直接擊殺 |

- 兩種魂壓可同時存在，各自開關。多個玩家的魂壓區域可以重疊。
- 釋放後不跟隨玩家；固定在原位置。新的生物走進固定範圍也會被定住，離開效果區或取消則恢復。
- 被壓住的生物完全停止移動與朝向更新；空中飛行、跳躍的生物會固定在當下高度。
- 固定期間使用灰色、深灰色灰塵粒子，在生物身旁向下流動；範圍模式有灰色地面圓環。
- **作用對象：一般生物（LivingEntity），不包含玩家。** 此版不處理 Minecraft 玩家 PvP 定身。
- 單體選取需準星瞄準，不能隔著實心方塊。
- 跨越維度不會讓範圍跟著你走。離開伺服器時，自己的魂壓會被取消。
- 只維持伺服器進程記憶體狀態，重新啟動後不會保留魂壓。

## 用 GitHub Actions 編譯（不需要自己安裝 Gradle）

1. 在 GitHub 建立新的 **Public/Private repository**，例如 `soul-pressure`。
2. 解壓本 ZIP，把 **ZIP 裡所有檔案及隱藏的 `.github` 資料夾** 上傳到 repo 根目錄（`build.gradle` 需在根目錄，不能多包一層資料夾）。
3. 前往 **Actions → Build Soul Pressure (Fabric 1.20.1) → Run workflow**。也可以直接 push 到 `main` 分支觸發。
4. 工作完成後開啟該次執行，在 **Artifacts** 下載 `soul-pressure-fabric-1.20.1`。
5. 解壓該 Artifact，取得 `soul-pressure-1.0.0.jar`。把它放到遊戲 `.minecraft/mods` 資料夾。

## 遊戲需求

- Minecraft Java **1.20.1**
- **Fabric Loader**（例如 0.16.14）
- **Fabric API 0.92.6+1.20.1**
- 1.20.1 遊戲環境通常使用 **Java 17**。
- 若使用多人伺服器，模組 JAR 與 Fabric API 須同時裝在伺服器及客戶端，按鍵才能運作。

## 本機編譯

安裝 Java 21 + Gradle 8.10.2，在專案根目錄輸入：

```bash
gradle build
```

輸出：`build/libs/soul-pressure-1.0.0.jar`。

## 需要注意

- 來源碼尚未在本環境完成 Minecraft/Fabric 依賴下載及實際編譯；真正的編譯結果請以 GitHub Actions 為準。
- 冰凍方式採取 Mixin 暫停生物 `tickMovement`，並由伺服器在 Tick 前後重新固定座標與朝向。生物特殊技能／其他模組的自訂移動邏輯可能需要額外相容性修正。
- 顯示粒子依賴用戶端粒子設定；若設成「最少」可能顯示比較少。
- 如果 R 或 V 與其他模組衝突，可在 **選項 → 控制 → 按鍵綁定 → 魂壓** 改鍵。
