FROM node:24-slim
ENV NODE_ENV=production
WORKDIR /app
COPY package.json server.js ./
USER node
CMD ["node", "server.js"]
