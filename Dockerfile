# 런타임에는 JRE 면 충분하다 - JDK 의 컴파일러·진단 도구는 싣지 않는다(gateway#247).
# apk upgrade: 베이스 이미지 태그가 갱신되기 전에 나온 OS 패키지 수정본을 받는다.
FROM eclipse-temurin:21-jre-alpine
RUN apk upgrade --no-cache
VOLUME /tmp
ARG JAR_FILE
COPY build/libs/*.jar app.jar
ENTRYPOINT ["java","-jar","/app.jar"]
