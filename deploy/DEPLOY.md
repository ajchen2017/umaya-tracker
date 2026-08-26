# 部署到 tracker.umaya.tw

已部署完成（2026-08-27）。這份文件記錄實際用的方式，供之後重新部署或除錯參考。

VPS 實際用 **Caddy**（自動 HTTPS，非 Nginx/certbot）+ **PM2**（跑在 `/root/.nvm` 的 node，非 systemd unit）+ 共用的 PostgreSQL（port `15432`，非預設 5432）。跟 gallery/h3-studio/stock 等專案同一套模式。

## 0. 前置（已完成）
1. DNS：`tracker.umaya.tw` A 記錄 → VPS 外網 IP `118.150.141.193`
2. RudyMap 地圖檔（`tileserver/maps/`、`tileserver/dem/`，非商用授權，不進 git）
3. GitHub repo `ajchen2017/umaya-tracker`（private），VPS 用專屬 deploy key（`~/.ssh/tracker_deploy_key`，唯讀）clone 到 `/home/aj/tracker`

## 1. 程式碼
```bash
# 本機 push
git push origin main

# VPS pull
ssh -p 2222 aj@192.168.18.3
cd /home/aj/tracker && git pull
```
`tileserver/maps/`、`tileserver/dem/`、`tileserver/bin/`（mapsforgesrv fatjar）不在 git 裡，用 scp 單獨傳：
```bash
scp -P 2222 -r tileserver/dem tileserver/maps tileserver/bin aj@192.168.18.3:/home/aj/tracker/tileserver/
```

## 2. 後端環境
```bash
ssh -p 2222 aj@192.168.18.3
cd /home/aj/tracker/backend
cp .env.example .env   # 編輯 DATABASE_URL（port 15432）/ JWT_SECRET / ADMIN_PASSWORD
sudo -u postgres psql -c "CREATE ROLE tracker_user WITH LOGIN PASSWORD '...';"
sudo -u postgres psql -c "CREATE DATABASE tracker OWNER tracker_user;"

# node/npm 只有 root 的 nvm 裝了，其他使用者要用 sudo + 指定 PATH
sudo env PATH=/root/.nvm/versions/node/v24.18.0/bin:/usr/bin:/bin npm install --omit=dev
sudo env PATH=/root/.nvm/versions/node/v24.18.0/bin:/usr/bin:/bin npm run migrate
sudo env PATH=/root/.nvm/versions/node/v24.18.0/bin:/usr/bin:/bin npm run import:signal-points
sudo env PATH=/root/.nvm/versions/node/v24.18.0/bin:/usr/bin:/bin npm run import:sheipa
```

## 3. Java（mapsforgesrv 用）
```bash
sudo apt-get install -y openjdk-21-jre-headless
```

## 4. PM2
```bash
PM2="sudo env PATH=/root/.nvm/versions/node/v24.18.0/bin:/usr/bin:/bin /root/.nvm/versions/node/v24.18.0/bin/pm2"

$PM2 start src/index.js --name tracker-backend --cwd /home/aj/tracker/backend
$PM2 start java --name tracker-tiles --cwd /home/aj/tracker/tileserver -- -jar bin/mapsforgesrv-fatjar.jar -c config
$PM2 save   # 重開機後 pm2-root.service 會自動復原
```

## 5. Caddy
在 `/etc/caddy/Caddyfile` 加一個 block（先備份原檔）：
```
tracker.umaya.tw {
	encode gzip

	handle /tiles/* {
		uri strip_prefix /tiles
		reverse_proxy 127.0.0.1:8486
	}

	handle {
		reverse_proxy 127.0.0.1:3000
	}
}
```
```bash
sudo caddy validate --config /etc/caddy/Caddyfile
sudo systemctl reload caddy   # 自動跟 Let's Encrypt 拿憑證，不需要 certbot
```

## 6. 驗證
- `https://tracker.umaya.tw/api/auth/register`（POST）能建帳號
- `https://tracker.umaya.tw/tiles/8/214/111.png?task=hiking` 能看到一張魯地圖圖磚（台灣中部座標；zoom 8 隨便挑經緯度算出的 x/y 很容易落在覆蓋範圍外，別懷疑是伺服器壞了）
- 手機 App 建立行程後，`https://tracker.umaya.tw/t/{shareToken}` 能看到軌跡

## 之後更新程式碼
```bash
ssh -p 2222 aj@192.168.18.3
cd /home/aj/tracker && git pull
$PM2 restart tracker-backend   # 只有 backend/ 改動時
```
