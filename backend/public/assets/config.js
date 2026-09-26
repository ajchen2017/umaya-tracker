// Self-hosted mapsforgesrv (renders RudyMap .map data into XYZ tiles on request).
// Caddy proxies /tiles/ -> mapsforgesrv on 127.0.0.1:8486; "task=hiking" selects
// the map/theme defined in tileserver/config/tasks/hiking.properties.
// "hiking-overlay" = transparent sea/outside, drawn on top of the OSM layer (see switchLayer).
const RUDY_TILE_URL = 'https://tracker.umaya.tw/tiles/{z}/{x}/{y}.png?task=hiking-overlay&transparent=true';

// Free, no API key needed. Usage-policy limits apply — fine for personal/small-group use.
// https://operations.osmfoundation.org/policies/tiles/
const OSM_TILE_URL = 'https://tile.openstreetmap.org/{z}/{x}/{y}.png';

// Poll interval while a hike is still active (ms).
const REFRESH_INTERVAL_MS = 30_000;
