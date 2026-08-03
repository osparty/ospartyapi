# The WebSocket upgrade, plus a read timeout well above the plugin's 20s ping so an idle party is not cut.
proxy_http_version 1.1;
proxy_set_header Upgrade $http_upgrade;
proxy_set_header Connection $connection_upgrade;
proxy_set_header Host $host;
proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
proxy_read_timeout 3600s;
proxy_send_timeout 3600s;
