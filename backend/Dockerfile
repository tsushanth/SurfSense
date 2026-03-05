FROM node:18-alpine

WORKDIR /app

# Copy package files first
COPY package*.json ./
RUN npm install --omit=dev

# Copy source files
COPY --chown=node:node . .

# Security hardening
RUN apk add --no-cache tini

USER node
ENTRYPOINT ["/sbin/tini", "--"]
CMD ["node", "server.js"]
