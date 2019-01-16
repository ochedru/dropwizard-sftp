package org.dhatim.dropwizard.sshd;

import org.apache.sshd.common.util.buffer.Buffer;
import org.apache.sshd.common.util.buffer.ByteArrayBuffer;
import org.apache.sshd.common.util.threads.CloseableExecutorService;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.subsystem.sftp.SftpErrorStatusDataHandler;
import org.apache.sshd.server.subsystem.sftp.SftpFileSystemAccessor;
import org.apache.sshd.server.subsystem.sftp.SftpSubsystem;
import org.apache.sshd.server.subsystem.sftp.UnsupportedAttributePolicy;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class ThrottledSftpSubsystem extends SftpSubsystem {

    private final long capacity;
    private final Lock lock = new ReentrantLock();
    private final Condition hasCapacity = lock.newCondition();
    private long usage;

    public ThrottledSftpSubsystem(CloseableExecutorService executorService, UnsupportedAttributePolicy policy, SftpFileSystemAccessor accessor, SftpErrorStatusDataHandler errorStatusDataHandler, long capacity) {
        super(executorService, policy, accessor, errorStatusDataHandler);
        this.capacity = capacity;
    }

    // Ugly: had to copy implementation of SftpSubsystem.data() because there is no possible hook for buffer send
    @Override
    public int data(ChannelSession channel, byte[] buf, int start, int len) throws IOException {
        buffer.compact();
        buffer.putRawBytes(buf, start, len);
        while (buffer.available() >= Integer.BYTES) {
            int rpos = buffer.rpos();
            int msglen = buffer.getInt();
            if (buffer.available() >= msglen) {
                int l = msglen + Integer.BYTES + Long.SIZE /* a bit extra */;
                ensureCapacity(l);  // Hook: make sure capacity is not exceeded
                Buffer b = new ByteArrayBuffer(l, false);
                b.putInt(msglen);
                b.putRawBytes(buffer.array(), buffer.rpos(), msglen);
                requests.add(b);
                buffer.rpos(rpos + msglen + Integer.BYTES);
            } else {
                buffer.rpos(rpos);
                break;
            }
        }
        return 0;
    }

    protected void ensureCapacity(int l) throws IOException {
        lock.lock();
        try {
            while (usage != 0 && usage + l > capacity) {
                hasCapacity.await(1, TimeUnit.SECONDS);
            }
            usage += l;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException(ex);
        } finally {
            lock.unlock();
        }
    }

    @Override
    protected void process(Buffer buffer) throws IOException {
        super.process(buffer);
        lock.lock();
        try {
            usage -= buffer.array().length;
            hasCapacity.signalAll();
        } finally {
            lock.unlock();
        }
    }
}
