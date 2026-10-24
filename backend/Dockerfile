FROM node:22-alpine
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --omit=dev
COPY server.example.js lgtv.js admin.js ./
COPY public ./public
USER node
ENV PORT=3000
EXPOSE 3000
CMD ["node", "server.example.js"]
