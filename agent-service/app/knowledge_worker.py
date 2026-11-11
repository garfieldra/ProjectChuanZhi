"""Independent, bounded-concurrency consumer for immutable knowledge index jobs."""
import asyncio
import json
import logging
import os

import aio_pika
import httpx

from .knowledge import VersionedIndex
from .settings import settings

log = logging.getLogger(__name__)
index = VersionedIndex(settings.milvus_uri)


async def execute(message):
    try:
        job_id = json.loads(message.body)["jobId"]
        async with httpx.AsyncClient(base_url=settings.java_internal_base_url, timeout=10,
                                     headers={"Authorization": "Bearer " + settings.agent_internal_service_token}) as client:
            path = f"/internal/v1/knowledge/jobs/{job_id}"
            response = await client.post(path + "/claim", json={"workerId": "knowledge-worker"})
            response.raise_for_status()
            job = response.json()
            if job.get("status") == "dead":
                await message.reject(requeue=False)
                return
            if job.get("status") in {"skip", "busy"}:
                await message.ack()
                return
            success = True
            async def heartbeat():
                while True:
                    await asyncio.sleep(30)
                    pulse = await client.post(path + "/heartbeat", headers={"X-Lease-Token": job["leaseToken"]})
                    pulse.raise_for_status()
            pulse = asyncio.create_task(heartbeat())
            try:
                async with asyncio.timeout(300):
                    await asyncio.to_thread(index.apply, job)
                if pulse.done():
                    pulse.result()
            except Exception as exc:
                success = False
                log.warning("knowledge_index_failed job=%s type=%s", job_id, type(exc).__name__)
            finally:
                pulse.cancel()
                await asyncio.gather(pulse, return_exceptions=True)
            response = await client.post(path + "/finish", headers={"X-Lease-Token": job["leaseToken"]},
                                         json={"success": success, "errorCode": None if success else "INDEX_OPERATION_FAILED"})
            response.raise_for_status()
            # Database retry scheduler redelivers failures after backoff; no hot requeue loop.
            if response.json().get("status") == "DEAD":
                await message.reject(requeue=False)
            else:
                await message.ack()
    except Exception as exc:
        log.warning("knowledge_worker_unavailable type=%s", type(exc).__name__)
        if not message.processed:
            await message.reject(requeue=False)


async def main():
    connection = await aio_pika.connect_robust(os.getenv("RABBITMQ_URL", "amqp://soundseek:soundseek@rabbitmq/"), fail_fast=False)
    channel = await connection.channel()
    await channel.set_qos(prefetch_count=1)
    queue = await channel.declare_queue("soundseek.knowledge-index.v1", durable=True,
        arguments={"x-dead-letter-exchange": "soundseek.dlx", "x-dead-letter-routing-key": "knowledge.failed"})
    await queue.consume(execute)
    try:
        await asyncio.Future()
    finally:
        await connection.close()


if __name__ == "__main__":
    asyncio.run(main())
