FROM node:22-alpine
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --omit=dev
COPY *.js *.mjs ./
COPY public ./public
USER node
ENV PORT=3000
EXPOSE 3000
CMD ["node", "server.example.js"]
