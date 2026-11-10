# What orange's container tests run the fake Claude Code in: a JDK (FakeClaude.java is a
# single-file program) and git.
FROM eclipse-temurin:21-jdk-alpine
RUN apk add --no-cache git && mkdir -p /home/agent && chmod 1777 /home/agent
COPY FakeClaude.java /opt/fake-claude/FakeClaude.java
