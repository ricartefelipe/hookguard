# A imagem oficial traz o gosu compilado com um Go antigo (1 crítico e 25 altos). Ele só serve para o entrypoint trocar de
# root para postgres; aqui o container já inicia como postgres (uid 70, dono do volume criado pela imagem oficial), então o
# gosu sai da imagem. postgres:16-alpine, fixada pelo digest; sem mudar a versão principal.
# postgres:16-alpine
FROM postgres@sha256:0cefaabd882e42bcb2a2f3dc56d3ce4770560ba62fd7f197f1a2e008fd5731ab
RUN apk upgrade --no-cache && rm -f /usr/local/bin/gosu
USER postgres
