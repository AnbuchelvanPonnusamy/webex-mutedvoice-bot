# Stage 1: Build the application
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

# Copy the source code
COPY . .

# Ensure Maven wrapper is executable
RUN chmod +x mvnw

# Build the JAR file
RUN ./mvnw clean package -DskipTests

# Stage 2: Run the application
FROM eclipse-temurin:21-jdk
WORKDIR /app

# Copy only the built JAR from the previous stage
COPY --from=build /app/target/webex-mutedvoice-bot-0.0.1-SNAPSHOT.jar app.jar

# Run the JAR
CMD ["java", "-jar", "app.jar"]
