FROM eclipse-temurin:25-jdk AS build
WORKDIR /app

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline

COPY src/ src/
# application-default.yaml is for local runs only - keep it out of the image
RUN rm -f src/main/resources/application-default.yaml
RUN ./mvnw -B -q clean package -Dmaven.test.skip=true

FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app

RUN addgroup --system taxify && adduser --system --ingroup taxify taxify
COPY --from=build /app/target/*.jar app.jar
RUN mkdir -p /data/fiscal-xml && chown -R taxify:taxify /app /data/fiscal-xml
USER taxify

EXPOSE 5000
# Heap = 45% of the machine's RAM (Java's default is 25%), leaving the rest to MySQL and the OS.
# Out of memory, the process exits and Docker's restart policy brings it back clean,
# instead of limping along half-broken. Override with JAVA_OPTS in the compose/.env.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=45 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
