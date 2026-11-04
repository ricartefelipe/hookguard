package main

import (
	caddycmd "github.com/caddyserver/caddy/v2/cmd"

	// Registra os módulos padrão do Caddy, usados pela configuração do proxy.
	_ "github.com/caddyserver/caddy/v2/modules/standard"
)

func main() {
	caddycmd.Main()
}
