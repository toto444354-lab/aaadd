# Soul Pressure / 魂壓 — Fabric 1.20.1

**v3 修正版**：修復 v2 進入世界時 `LivingEntityRendererMixin` 的 `@Shadow getTexture` 遊戲崩潰。

## 按鍵
- `R`：固定原地的範圍魂壓（水平半徑 30 格、上下各 30 格）。再按一次關閉。
- `V`：準星鎖定 50 格內一隻生物（不能隔牆）。再按一次解除。

## 效果
- 被壓生物無法移動、旋轉，滯空時固定高度。
- 左鍵擊中被魂壓控制的生物時一擊擊殺。
- 灰黑色向下的重力壓迫粒子；受控生物呈透明灰色（包含基礎模型，部分附加裝備/效果視各自渲染方式而定）。
- 生物不含其他玩家；範圍魂壓施放後不跟隨玩家移動。

## 從 GitHub Actions 編譯
1. 解壓 ZIP，**上傳 ZIP 內所有檔案**到你的 Repository 根目錄，取代舊版原始碼。
2. 使用 `.github/workflows/build.yml`，內含 Gradle 8.11.1。
3. 進入 GitHub → Actions → Build Soul Pressure → Run workflow。
4. 成功後在 Artifacts 下載 `soul-pressure-fabric-1.20.1`，將其中的 `.jar` 與 Fabric API 安裝進 `mods`。
5. **先刪除舊版 soul-pressure JAR**，不能同時裝 v2 和 v3。  

注意：目前尚未在本環境實際運行 Minecraft 驗證，若編譯或進入世界仍有錯誤，請提供 Actions 的 Build 日誌或最新遊戲日誌以便定位。
