FROM node:22-alpine AS build
WORKDIR /workspace
COPY package.json package-lock.json ./
RUN npm ci
COPY index.html vite.config.js ./
COPY src src
COPY public public
RUN npm run build

FROM nginx:1.27-alpine
COPY --from=build /workspace/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 8080

