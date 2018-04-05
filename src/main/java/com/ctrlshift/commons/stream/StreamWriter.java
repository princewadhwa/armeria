package com.ctrlshift.commons.stream;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import javax.annotation.CheckReturnValue;

import org.reactivestreams.Subscriber;

//import com.linecorp.armeria.unsafe.ByteBufHttpData;

import io.netty.buffer.ByteBuf;
import io.netty.util.ReferenceCounted;

/**
 * Produces the objects to be published by a {@link StreamMessage}.
 *
 * <h3 id="reference-counted">Life cycle of reference-counted objects</h3>
 *
 * <p>When the following methods are given with a {@link ReferenceCounted} object, such as {@link ByteBuf} and
 * {@link ByteBufHttpData}, or the {@link Supplier} that provides such an object:
 *
 * <ul>
 *   <li>{@link #tryWrite(Object)}</li>
 *   <li>{@link #tryWrite(Supplier)}</li>
 *   <li>{@link #write(Object)}</li>
 *   <li>{@link #write(Supplier)}</li>
 * </ul>
 * the object will be released automatically by the stream when it's no longer in use, such as when:
 * <ul>
 *   <li>The method returns {@code false} or raises an exception.</li>
 *   <li>The {@link Subscriber} of the stream consumes it.</li>
 *   <li>The stream is cancelled, aborted or failed.</li>
 * </ul>
 *
 * @param <T> the type of the stream element
 */
public interface StreamWriter<T> {

    /**
     * Returns {@code true} if the {@link StreamMessage} is open.
     */
    boolean isOpen();

    /**
     * Writes the specified object to the {@link StreamMessage}. The written object will be transferred to the
     * {@link Subscriber}.
     *
     * @throws IllegalStateException if the stream was already closed
     * @throws IllegalArgumentException if the publication of the specified object has been rejected
     * @see <a href="#reference-counted">Life cycle of reference-counted objects</a>
     */
    default void write(T o) {
        if (!tryWrite(o)) {
            throw new IllegalStateException("stream closed");
        }
    }

    /**
     * Writes the specified object {@link Supplier} to the {@link StreamMessage}. The object provided by the
     * {@link Supplier} will be transferred to the {@link Subscriber}.
     *
     * @throws IllegalStateException if the stream was already closed.
     * @see <a href="#reference-counted">Life cycle of reference-counted objects</a>
     */
    default void write(Supplier<? extends T> o) {
        if (!tryWrite(o)) {
            throw new IllegalStateException("stream closed");
        }
    }

    /**
     * Writes the specified object to the {@link StreamMessage}. The written object will be transferred to the
     * {@link Subscriber}.
     *
     * @return {@code true} if the specified object has been scheduled for publication. {@code false} if the
     *         stream has been closed already.
     *
     * @throws IllegalArgumentException if the publication of the specified object has been rejected
     * @see <a href="#reference-counted">Life cycle of reference-counted objects</a>
     */
    @CheckReturnValue
    boolean tryWrite(T o);

    /**
     * Writes the specified object {@link Supplier} to the {@link StreamMessage}. The object provided by the
     * {@link Supplier} will be transferred to the {@link Subscriber}.
     *
     * @return {@code true} if the specified object has been scheduled for publication. {@code false} if the
     *         stream has been closed already.
     * @see <a href="#reference-counted">Life cycle of reference-counted objects</a>
     */
    @CheckReturnValue
    default boolean tryWrite(Supplier<? extends T> o) {
        return tryWrite(o.get());
    }

    /**
     * Performs the specified {@code task} when there are enough demands from the {@link Subscriber}.
     *
     * @return the future that completes successfully when the {@code task} finishes or
     *         exceptionally when the {@link StreamMessage} is closed unexpectedly.
     */
    CompletableFuture<Void> onDemand(Runnable task);

    /**
     * Closes the {@link StreamMessage} successfully. {@link Subscriber#onComplete()} will be invoked to
     * signal that the {@link Subscriber} has consumed the stream completely.
     */
    void close();

    /**
     * Closes the {@link StreamMessage} exceptionally. {@link Subscriber#onError(Throwable)} will be invoked to
     * signal that the {@link Subscriber} did not consume the stream completely.
     */
    void close(Throwable cause);

    /**
     * Writes the given object and closes the stream successfully.
     */
    default void close(T obj) {
        write(obj);
        close();
    }
}