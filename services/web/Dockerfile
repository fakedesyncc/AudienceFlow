FROM node:24-alpine AS build

WORKDIR /src
COPY package*.json ./
RUN npm ci

COPY . .
ARG VITE_API_URL=http://localhost:8080/api
ARG VITE_BASE_PATH=/
ENV VITE_API_URL=${VITE_API_URL}
ENV VITE_BASE_PATH=${VITE_BASE_PATH}
RUN npm run build

FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /src/dist /usr/share/nginx/html
EXPOSE 80
