# The image every contained run starts from: Claude Code, git, ripgrep and a user that is not root.
# A repo's toolchain is the base image: any Debian or Ubuntu image that has it (build.sh --base).
ARG BASE_IMAGE=debian:bookworm-slim
FROM ${BASE_IMAGE}
ARG CLAUDE_CODE_VERSION=2.1.322
ARG AGENT_UID=1000
ARG AGENT_GID=1000
USER root
RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates curl git ripgrep \
 && rm -rf /var/lib/apt/lists/*
# One self-contained binary, pinned; the build fails when the version is not the one asked for.
RUN curl -fsSL https://claude.ai/install.sh | bash -s -- "${CLAUDE_CODE_VERSION}" \
 && install -m 0755 "$(readlink -f /root/.local/bin/claude)" /usr/local/bin/claude \
 && rm -rf /root/.local /root/.claude /root/.claude.json \
 && claude --version | grep -F "${CLAUDE_CODE_VERSION}"
RUN groupadd --non-unique --gid "${AGENT_GID}" agent \
 && useradd --non-unique --uid "${AGENT_UID}" --gid "${AGENT_GID}" --create-home --home-dir /home/agent --shell /bin/bash agent
ENV HOME=/home/agent LANG=C.UTF-8 DISABLE_AUTOUPDATER=1
USER agent
WORKDIR /home/agent
