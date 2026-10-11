FROM node:22-bookworm-slim AS dependencies
WORKDIR /app
RUN apt-get update
RUN apt-get install -y --no-install-recommends openssl ca-certificates
RUN rm -rf /var/lib/apt/lists/*
COPY package.json package-lock.json ./
RUN npm ci

FROM dependencies AS build
COPY . .
ENV NEXT_TELEMETRY_DISABLED=1
RUN npm run build
# Remove test tools and the embedded PostgreSQL binary before packaging.
RUN npm prune --omit=dev

FROM node:22-bookworm-slim AS runner
WORKDIR /app
RUN apt-get update
RUN apt-get install -y --no-install-recommends openssl ca-certificates
RUN rm -rf /var/lib/apt/lists/*
RUN groupadd --gid 10001 life
RUN useradd --uid 10001 --gid life --no-create-home life
ENV NODE_ENV=production NEXT_TELEMETRY_DISABLED=1 HOSTNAME=0.0.0.0 PORT=3000 HOME=/tmp
COPY --from=build --chown=life:life /app/.next/standalone ./
COPY --from=build --chown=life:life /app/.next/static ./.next/static
COPY --from=build --chown=life:life /app/node_modules ./node_modules
COPY --from=build --chown=life:life /app/prisma ./prisma
COPY --from=build --chown=life:life /app/scripts/bootstrap.ts ./scripts/bootstrap.ts
COPY --from=build --chown=life:life /app/src/lib ./src/lib
COPY --chown=life:life deploy/entrypoint.sh ./entrypoint.sh
RUN chmod 500 /app/entrypoint.sh
USER life
EXPOSE 3000
ENTRYPOINT ["/app/entrypoint.sh"]
