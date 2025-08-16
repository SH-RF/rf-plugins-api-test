FROM container-registry.oracle.com/graalvm/native-image:21 AS build
WORKDIR /app
COPY . .
RUN sed -i 's/\r$//' ./gradlew
RUN chmod +x ./gradlew

RUN ./gradlew clean build -Dquarkus.native.enabled=true \
    -Dquarkus.native.container-build=false \
    -Dquarkus.native.builder-image=graalvm \
    -Dquarkus.native.container-runtime=docker \
    -Dquarkus.mongodb.connection-string=${MONGO_URI} \
    -Dquarkus.otel.exporter.otlp.endpoint=${OTEL_URI}

FROM registry.access.redhat.com/ubi9/ubi-minimal:9.2
WORKDIR /app
RUN chown 1001 /app && chmod "g+rwX" /app && chown 1001:root /app
COPY --from=build --chown=1001:root /app/build/*-runner /app/application

ENV MONGO_URI=${MONGO_URI}
ENV OTEL_URI=${OTEL_URI}

EXPOSE 8080
USER 1001

ENTRYPOINT [ \
"/app/application", \
"-Dquarkus.http.host=0.0.0.0", \
"-Dquarkus.mongodb.connection-string=${MONGO_URI}", \
"-Dquarkus.otel.exporter.otlp.endpoint=${OTEL_URI}" \
]

# Testing fork sync
# Test sync fork four
# test test
#Test update docker file
