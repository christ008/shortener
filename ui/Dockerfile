# The app's static files and their policy, served by an unprivileged nginx. The edge forwards /app here.
#
#   docker build --build-arg VITE_ISSUER=https://shortener.example.com/realms/shortener -t shortener-ui:VERSION ui
#
# VITE_ISSUER is the identity provider the browser calls, public: the build puts its origin in the policy's connect-src.

FROM node:24.14.0-alpine AS build
ARG VITE_ISSUER
ENV VITE_ISSUER=${VITE_ISSUER}
WORKDIR /app
RUN corepack enable
COPY .yarnrc.yml package.json yarn.lock ./
RUN yarn install --immutable
COPY . .
RUN yarn build

FROM nginxinc/nginx-unprivileged:1.31.5-alpine
COPY --from=build /app/dist/client /usr/share/nginx/html/app
COPY --from=build /app/dist/csp.conf /etc/nginx/csp.conf
COPY nginx/security-headers.conf /etc/nginx/security-headers.conf
COPY nginx/nginx.conf /etc/nginx/conf.d/default.conf
