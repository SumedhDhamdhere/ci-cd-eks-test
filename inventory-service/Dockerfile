FROM eclipse-temurin:25-jre-alpine
WORKDIR /app
COPY target/inventory-service-1.0.0.jar app.jar
EXPOSE 8084
ENTRYPOINT ["java","-jar","-Dspring.profiles.active=${SPRING_PROFILE:-prod}","app.jar"]
