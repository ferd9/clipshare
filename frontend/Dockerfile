FROM node:22-slim

WORKDIR /app

COPY package.json package-lock.json ./
RUN npm install

COPY . .

EXPOSE 5173

# El comando real (npm run dev -- --host) lo define docker-compose.yml;
# esto queda como fallback si el contenedor se corre suelto.
CMD ["npm", "run", "dev", "--", "--host"]
