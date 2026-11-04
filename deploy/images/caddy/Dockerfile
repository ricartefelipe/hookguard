# Caddy compilado com Go e dependências corrigidos. A imagem oficial (caddy:2.x-alpine) traz o binário compilado com um
# Go antigo e módulos com falhas altas e críticas já corrigidas. Mesmo layout da imagem oficial (/etc/caddy, /data,
# /config), mas sem root: o Compose de produção ajusta o dono dos volumes de certificados antes de subir o Caddy.
# golang:1.27-alpine
FROM golang@sha256:d200d2dd76b03a99b52651fd1325aff7dcd35422c35c01bac56571b0507f3deb AS build
WORKDIR /src
COPY main.go go.mod go.sum ./
RUN CGO_ENABLED=0 go build -trimpath -ldflags "-s -w" -o /caddy .

# alpine:3.22
FROM alpine@sha256:2e0b81d60597f449baa56dc25d355367869d2dca4cd57e866f1e2b52af3eb55f
# Correções de segurança do Alpine publicadas depois da imagem base entram em cada build.
# O índice é baixado uma vez, com nova tentativa se o espelho falhar.
RUN (apk update || (sleep 5 && apk update) || (sleep 15 && apk update)) \
  && apk upgrade \
  && apk add ca-certificates mailcap \
  && addgroup -S -g 1001 caddy && adduser -S -u 1001 -G caddy caddy \
  && mkdir -p /etc/caddy /data/caddy /config/caddy && chown -R caddy:caddy /data /config \
  && rm -rf /var/cache/apk/*
COPY --from=build /caddy /usr/bin/caddy
ENV XDG_CONFIG_HOME=/config XDG_DATA_HOME=/data
USER caddy
EXPOSE 80 443 443/udp
WORKDIR /srv
CMD ["caddy", "run", "--config", "/etc/caddy/Caddyfile", "--adapter", "caddyfile"]
