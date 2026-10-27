package com.flipperai.bridge

import java.io.ByteArrayOutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

class RpcException(message: String) : Exception(message)

class RpcReply(val id: Int, val status: Int, val hasNext: Boolean, val field: Int, val content: ByteArray?)

class FlipperRpc(private val transport: Transport) {

    private val inbox = ArrayBlockingQueue<RpcReply>(256)
    private val pending = ByteArrayOutputStream()
    private var nextId = 1

    fun open(startCli: Boolean) {
        transport.rawSink = { feed(it) }
        if (startCli) {
            transport.sendRaw("start_rpc_session\r".toByteArray(Charsets.US_ASCII))
            Thread.sleep(900)
            synchronized(pending) { pending.reset() }
            inbox.clear()
        }
    }

    fun close() {
        try {
            val id = nextId++
            transport.sendRaw(frame(Pb.uint(1, id.toLong()) + Pb.bytes(19, ByteArray(0))))
            Thread.sleep(200)
        } catch (e: Exception) {
        }
        transport.rawSink = null
    }

    private fun frame(message: ByteArray): ByteArray = Pb.varint(message.size.toLong()) + message

    private fun feed(data: ByteArray) {
        synchronized(pending) {
            pending.write(data)
            val all = pending.toByteArray()
            var pos = 0
            while (true) {
                val reader = Pb.Reader(all, pos)
                val len = try {
                    reader.readVarint().toInt()
                } catch (e: IllegalStateException) {
                    break
                }
                var lenSize = 1
                var probe = len.toLong()
                while (probe >= 0x80) {
                    lenSize++
                    probe = probe ushr 7
                }
                if (len < 0 || pos + lenSize + len > all.size) break
                val body = all.copyOfRange(pos + lenSize, pos + lenSize + len)
                pos += lenSize + len
                try {
                    inbox.offer(parse(body))
                } catch (e: Exception) {
                }
            }
            pending.reset()
            if (pos < all.size) pending.write(all, pos, all.size - pos)
        }
    }

    private fun parse(body: ByteArray): RpcReply {
        val reader = Pb.Reader(body)
        var id = 0
        var status = 0
        var hasNext = false
        var field = 0
        var content: ByteArray? = null
        while (true) {
            val f = reader.next() ?: break
            when (f.number) {
                1 -> id = f.value.toInt()
                2 -> status = f.value.toInt()
                3 -> hasNext = f.value != 0L
                else -> {
                    field = f.number
                    content = f.data
                }
            }
        }
        return RpcReply(id, status, hasNext, field, content)
    }

    private fun send(id: Int, hasNext: Boolean, field: Int, content: ByteArray) {
        val message = Pb.uint(1, id.toLong()) + Pb.bool(3, hasNext) + Pb.bytes(field, content)
        transport.sendRaw(frame(message))
    }

    private fun await(id: Int, timeoutMs: Long): List<RpcReply> {
        val replies = ArrayList<RpcReply>()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) throw RpcException("Flipper did not answer in time")
            val reply = inbox.poll(left, TimeUnit.MILLISECONDS) ?: throw RpcException("Flipper did not answer in time")
            if (reply.id != id) continue
            if (reply.status != 0) throw RpcException("Flipper error ${statusName(reply.status)}")
            replies.add(reply)
            if (!reply.hasNext) return replies
        }
    }

    private fun statusName(code: Int) = when (code) {
        4 -> "busy (close the running app on the Flipper)"
        5 -> "SD card not ready"
        6 -> "exists"
        7 -> "not found"
        9 -> "access denied"
        else -> code.toString()
    }

    fun call(field: Int, content: ByteArray, timeoutMs: Long = 8000): List<RpcReply> {
        val id = nextId++
        send(id, false, field, content)
        return await(id, timeoutMs)
    }

    fun ping(): Boolean = try {
        call(5, Pb.bytes(1, byteArrayOf(1, 2, 3)), 4000)
        true
    } catch (e: RpcException) {
        false
    }

    fun list(path: String): List<Pair<String, Boolean>> {
        val out = ArrayList<Pair<String, Boolean>>()
        for (reply in call(7, Pb.str(1, path))) {
            val content = reply.content ?: continue
            val reader = Pb.Reader(content)
            while (true) {
                val f = reader.next() ?: break
                if (f.number != 1 || f.data == null) continue
                val file = Pb.Reader(f.data)
                var name = ""
                var dir = false
                while (true) {
                    val ff = file.next() ?: break
                    if (ff.number == 1) dir = ff.value == 1L
                    if (ff.number == 2 && ff.data != null) name = String(ff.data, Charsets.UTF_8)
                }
                if (name.isNotEmpty()) out.add(name to dir)
            }
        }
        return out
    }

    fun mkdir(path: String) {
        try {
            call(13, Pb.str(1, path))
        } catch (e: RpcException) {
            if (e.message?.contains("exists") != true) throw e
        }
    }

    fun delete(path: String) {
        call(12, Pb.str(1, path) + Pb.bool(2, false))
    }

    fun rename(from: String, to: String) {
        call(30, Pb.str(1, from) + Pb.str(2, to))
    }

    fun md5(path: String): String {
        val reply = call(14, Pb.str(1, path), 20000).firstOrNull()?.content ?: return ""
        val reader = Pb.Reader(reply)
        while (true) {
            val f = reader.next() ?: break
            if (f.number == 1 && f.data != null) return String(f.data, Charsets.UTF_8)
        }
        return ""
    }

    fun write(path: String, data: ByteArray, chunk: Int, delayMs: Long, onProgress: (Int) -> Unit) {
        val id = nextId++
        var offset = 0
        while (offset < data.size) {
            val end = minOf(offset + chunk, data.size)
            val last = end == data.size
            val file = Pb.bytes(4, data.copyOfRange(offset, end))
            send(id, !last, 11, Pb.str(1, path) + Pb.bytes(2, file))
            offset = end
            onProgress(offset * 100 / data.size)
            if (!last && delayMs > 0) Thread.sleep(delayMs)
        }
        await(id, 30000)
    }
}
