#!/bin/sh
set -eu
node node_modules/prisma/build/index.js migrate deploy
node node_modules/tsx/dist/cli.mjs scripts/bootstrap.ts
exec node server.js
