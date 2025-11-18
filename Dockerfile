FROM eclipse-temurin:17-jdk

# Installe kubectl (version fixe) + mysql-client
RUN apt-get update && \
    apt-get install -y curl default-mysql-client && \
    curl -L https://dl.k8s.io/release/v1.29.3/bin/linux/amd64/kubectl -o /usr/local/bin/kubectl && \
    chmod +x /usr/local/bin/kubectl

WORKDIR /app

COPY target/server-worker-1.0-SNAPSHOT.jar app.jar

CMD ["java", "-jar", "app.jar"]
