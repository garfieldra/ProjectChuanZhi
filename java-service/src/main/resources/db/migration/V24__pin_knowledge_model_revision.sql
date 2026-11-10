-- Immutable upstream revision used by the tested FastEmbed baseline.
UPDATE knowledge_index_generation
SET payload_json=JSON_SET(payload_json,'$.modelRevision','46fbe35fd4374a00fee7de77dfddaeb6dd6a2c59')
WHERE id=1 AND JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.modelRevision'))='baseline-v1';
