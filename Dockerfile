
FROM eclipse-temurin:21-jdk

# Set the working directory
WORKDIR /app

# Copy the jar file from target folder
COPY target/*.jar app.jar

# Expose port (Render injects PORT env var)
EXPOSE 8080

# Start the Spring Boot application
ENTRYPOINT ["java", "-jar", "app.jar"]
