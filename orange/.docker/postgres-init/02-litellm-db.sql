-- Database for the LiteLLM gateway (virtual keys, spend logs). Runs only when the Postgres
-- volume is first created; for an existing volume the compose service litellm-db-init
-- creates it on demand.
CREATE DATABASE litellm;
