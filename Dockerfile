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
ENTRYPOINT ["java", "-jar", "app.jar"]
