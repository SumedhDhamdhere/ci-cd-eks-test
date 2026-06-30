FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY target/product-service-1.0.0.jar app.jar
EXPOSE 8082
ENTRYPOINT ["java","-jar","-Dspring.profiles.active=${SPRING_PROFILE:-prod}","app.jar"]
