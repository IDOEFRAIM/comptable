# Build multi-etapes : etape 1 compile le bundle Angular en mode production (verification de
# template stricte deja validee en dev via `ng build --configuration=production`), etape 2 sert
# les fichiers statiques par nginx - l'image finale ne contient ni Node ni le code source.
FROM node:20-alpine AS build
WORKDIR /build

COPY package.json package-lock.json ./
RUN npm ci

COPY . .
RUN npx ng build --configuration=production

FROM nginx:1.27-alpine AS runtime

# outputPath du projet (angular.json) : le builder Angular "application" (esbuild) publie sous
# dist/frontend/browser/, pas directement dist/frontend/ - a ne pas oublier si l'outputPath change.
COPY --from=build /build/dist/frontend/browser /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80
