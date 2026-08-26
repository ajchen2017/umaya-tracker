# 部署到 tracker.umaya.tw

## 0. 你要先自己做的事（無法代勞）
1. **DNS**：幫 `tracker.umaya.tw` 加一筆 A 記錄指到 VPS 外網 IP `118.150.141.193`
2. **RudyMap 地圖檔**：到 https://rudymap.tw/ 依授權下載 Mapsforge `.map` 檔（非商用），存好備用
3. **Google Maps API Key**：到 https://console.cloud.google.com/google/maps-apis 建立 key，限制只給 `tracker.umaya.tw` 網域使用，填進 `backend/public/assets/config.js` 的 `GOOGLE_MAPS_API_KEY`

## 1. 上傳程式碼到 VPS
```bash
scp -P 2222 -r backend tileserver aj@192.168.18.3:/opt/tracker/
scp -P 2222 deploy/*.service aj@192.168.18.3:/tmp/
```

## 2. VPS 上安裝相依套件（SSH 進去後）
```bash
ssh -p 2222 aj@192.168.18.3

# Node.js 後端
cd /opt/tracker/backend
cp .env.example .env   # 編輯 DATABASE_URL / JWT_SECRET
npm install
npm run migrate        # 建 tracker 資料庫的 schema（先用 psql 開好 tracker 這個 DB）

# mapsforgesrv（需要 Java 17+）
cd /opt/tracker/tileserver
curl -fsSL -o bin/mapsforgesrv-fatjar.jar https://github.com/telemaxx/mapsforgesrv/releases/latest/download/mapsforgesrv-fatjar.jar
# 把 MOI_OSM_Taiwan_TOPO_Rudy.map 放到 /opt/tracker/tileserver/maps/（跟 config/tasks/hiking.properties 的 mapfiles 對應）
```

## 3. 開機自動啟動（systemd）
```bash
sudo mv /tmp/*.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now tracker-backend mapsforgesrv
```

## 4. Nginx + HTTPS
```bash
sudo cp deploy/nginx-tracker.umaya.tw.conf /etc/nginx/sites-available/tracker.umaya.tw
sudo ln -s /etc/nginx/sites-available/tracker.umaya.tw /etc/nginx/sites-enabled/
sudo nginx -t && sudo systemctl reload nginx
sudo certbot --nginx -d tracker.umaya.tw
```

## 5. 驗證
- `https://tracker.umaya.tw/api/auth/register`（POST）能建帳號
- `https://tracker.umaya.tw/tiles/8/218/104.png?task=hiking` 能看到一張魯地圖圖磚
- 手機 App 建立行程後，`https://tracker.umaya.tw/t/{shareToken}` 能看到軌跡
