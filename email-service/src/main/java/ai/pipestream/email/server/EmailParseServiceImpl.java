package ai.pipestream.email.server;

import ai.pipestream.email.document.EmailDocumentFold;
import ai.pipestream.email.parse.EmailSniffer;
import ai.pipestream.email.parse.EmlParser;
import ai.pipestream.email.parse.HeaderProjection;
import ai.pipestream.email.parse.InvalidEmailException;
import ai.pipestream.email.parse.MsgParser;
import ai.pipestream.email.parse.ParseCancelledException;
import ai.pipestream.email.parse.ParseOptions;
import ai.pipestream.email.parse.ParseSink;
import ai.pipestream.email.parse.UnsupportedFormatException;
import ai.pipestream.email.v1.Attachment;
import ai.pipestream.email.v1.BodyPart;
import ai.pipestream.email.v1.EmailFormat;
import ai.pipestream.email.v1.EmailInfo;
import ai.pipestream.email.v1.EmailParseServiceGrpc;
import ai.pipestream.email.v1.GetServiceInfoRequest;
import ai.pipestream.email.v1.GetServiceInfoResponse;
import ai.pipestream.email.v1.ParseEmailOptions;
import ai.pipestream.email.v1.ParseEmailRequest;
import ai.pipestream.email.v1.ParseEmailResponse;
import ai.pipestream.email.v1.ParseStatus;
import ai.pipestream.email.v1.UiInfo;
import com.google.protobuf.ByteString;
import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

/**
 * The gRPC face over the two parsers.
 *
 * <p>Live streaming is the contract, not an optimization. For an .eml the
 * server watches the growing upload for the blank line that ends the header
 * block and emits EmailInfo right there -- on a chunked upload that lands
 * before the client has finished sending. The MIME walk then streams every
 * body part and attachment as it decodes it, and ParseStatus arrives last as
 * a trailer of counts and warnings. Nothing is collected and flushed at the
 * end.
 *
 * <p>Bytes never leave memory: chunks accumulate under a hard cap, parse on
 * a virtual thread, and are released when the RPC ends. A semaphore bounds
 * concurrent parses because MIME trees and MAPI property maps are
 * heap-hungry, so the interesting limit is memory, not CPU.
 */
public final class EmailParseServiceImpl extends EmailParseServiceGrpc.EmailParseServiceImplBase {

  /** Semantic version of this server build. */
  public static final String SERVICE_VERSION = "0.1.0";

  /** Wire API version, matching the proto package suffix. */
  public static final String API_VERSION = "v1";

  private static final long MIB = 1024L * 1024L;

  /** Read once: the answer cannot change while the JVM is up. */
  private static final String MAIL_VERSION = readMailVersion();

  private final long maxDocumentBytes;
  private final long maxAttachmentBytes;
  private final int maxConcurrentParses;
  private final Semaphore parseSlots;
  private final ExecutorService executor;
  private final ParseMetrics metrics = new ParseMetrics();

  public EmailParseServiceImpl(
      long maxDocumentBytes,
      long maxAttachmentBytes,
      int maxConcurrentParses,
      ExecutorService executor) {
    this.maxDocumentBytes = maxDocumentBytes;
    this.maxAttachmentBytes = maxAttachmentBytes;
    this.maxConcurrentParses = maxConcurrentParses;
    this.parseSlots = new Semaphore(maxConcurrentParses);
    this.executor = executor;
  }

  /** The lifetime counters this service keeps; the launcher reports them. */
  public ParseMetrics metrics() {
    return metrics;
  }

  @Override
  public StreamObserver<ParseEmailRequest> parseEmail(
      StreamObserver<ParseEmailResponse> responses) {
    return new Upload(responses).attach();
  }

  @Override
  public void getServiceInfo(
      GetServiceInfoRequest request, StreamObserver<GetServiceInfoResponse> responses) {
    responses.onNext(
        GetServiceInfoResponse.newBuilder()
            .setServiceVersion(SERVICE_VERSION)
            .setApiVersion(API_VERSION)
            .setMailVersion(MAIL_VERSION)
            .setPoiVersion(org.apache.poi.Version.getVersion())
            .addSupportedFormats(EmailFormat.EMAIL_FORMAT_EML)
            .addSupportedFormats(EmailFormat.EMAIL_FORMAT_MSG)
            .setMaxDocumentBytes(maxDocumentBytes)
            .setMaxAttachmentBytes(maxAttachmentBytes)
            .setMaxConcurrentParses(maxConcurrentParses)
            .setUi(
                UiInfo.newBuilder()
                    .setTitle("Email")
                    .setPath("/ui/email")
                    .setDescription(
                        "Email bytes to typed events: envelope, body, attachments")
                    .build())
            .build());
    responses.onCompleted();
  }

  /**
   * Jakarta Mail version, read from the manifest of the jar that provides
   * it. The jar ships an OSGi Bundle-Version rather than the
   * Implementation-Version {@code Package} exposes, so the manifest is read
   * directly; outside a jar there is nothing to report.
   */
  private static String readMailVersion() {
    try {
      URL clazz = jakarta.mail.Session.class.getResource("Session.class");
      if (clazz == null || !"jar".equals(clazz.getProtocol())) {
        return "unknown";
      }
      String jar = clazz.toString().substring(0, clazz.toString().indexOf('!') + 2);
      try (InputStream stream = URI.create(jar + "META-INF/MANIFEST.MF").toURL().openStream()) {
        Attributes attributes = new Manifest(stream).getMainAttributes();
        for (String key : new String[] {
            "Implementation-Version", "Bundle-Version", "Specification-Version"}) {
          String value = attributes.getValue(key);
          if (value != null && !value.isBlank()) {
            return value.strip();
          }
        }
      }
      return "unknown";
    } catch (IOException | RuntimeException unavailable) {
      return "unknown";
    }
  }

  /**
   * Writes events to the wire as the parsers produce them, applies the
   * client's attachment-listing choice, and keeps the counts the trailer
   * reports. Emission is synchronized because the envelope can be written
   * from the request thread while the body arrives from a parse thread.
   *
   * <p>When the client asked for one, every event also passes through the
   * Document fold on its way out, so the projection is built from exactly
   * what the wire carried rather than from a second traversal of the message.
   * Without that option the fold is null and costs nothing.
   */
  private final class Sink implements ParseSink {

    private final StreamObserver<ParseEmailResponse> responses;
    private final ParseOptions options;
    private final EmailDocumentFold fold;
    private final BooleanSupplier cancelled;
    private final List<String> warnings = new ArrayList<>();
    private boolean infoSent;
    private int bodyParts;
    private int attachments;
    private long attachmentBytes;

    private Sink(
        StreamObserver<ParseEmailResponse> responses,
        ParseOptions options,
        boolean emitDocument,
        BooleanSupplier cancelled) {
      this.responses = responses;
      this.options = options;
      this.cancelled = cancelled;
      this.fold = emitDocument
          ? new EmailDocumentFold(SERVICE_VERSION,
              new EmailDocumentFold.SourceOrigin(options.filename(), options.contentType()))
          : null;
    }

    /** The one way out. Everything the client sees, the fold sees first. */
    private void emit(ParseEmailResponse event) {
      if (fold != null) {
        fold.consume(event);
      }
      responses.onNext(event);
    }

    @Override
    public synchronized void info(EmailInfo info) {
      if (infoSent) {
        return;
      }
      infoSent = true;
      emit(ParseEmailResponse.newBuilder().setEmailInfo(info).build());
    }

    @Override
    public synchronized void bodyPart(BodyPart part) {
      bodyParts++;
      emit(ParseEmailResponse.newBuilder().setBodyPart(part).build());
    }

    @Override
    public synchronized void attachment(Attachment attachment) {
      attachments++;
      attachmentBytes += attachment.getSizeBytes();
      if (options.emitAttachments()) {
        emit(ParseEmailResponse.newBuilder().setAttachment(attachment).build());
      }
    }

    @Override
    public synchronized void warn(String warning) {
      warnings.add(warning);
    }

    @Override
    public void checkpoint() {
      if (cancelled.getAsBoolean()) {
        throw new ParseCancelledException();
      }
    }

    private synchronized boolean infoSent() {
      return infoSent;
    }

    /**
     * Closes the stream: the trailer is built first, folded so the projection
     * has seen the whole stream, then the document goes out ahead of it. The
     * status stays last, because its arrival is what makes the parse a
     * success and nothing may follow it.
     *
     * <p>The message bytes come in whole rather than as a length because the
     * fold fingerprints them for {@code DocumentOrigin.binary_hash}. They are
     * already buffered for the parse, so this costs one digest and no copy.
     */
    private synchronized void trailer(byte[] message) {
      ParseStatus.Builder status = ParseStatus.newBuilder()
          .setState(warnings.isEmpty() ? ParseStatus.State.STATE_OK
              : ParseStatus.State.STATE_PARTIAL)
          .addAllWarnings(warnings)
          .setBodyParts(bodyParts)
          .setAttachments(attachments)
          .setAttachmentBytes(attachmentBytes)
          .setMessageBytes(message.length);
      ParseEmailResponse trailer = ParseEmailResponse.newBuilder().setStatus(status).build();
      if (fold != null) {
        fold.consume(trailer);
        fold.sourceBytes(message);
        responses.onNext(ParseEmailResponse.newBuilder().setDocument(fold.take()).build());
      }
      responses.onNext(trailer);
      metrics.contentEmitted(bodyParts, attachments);
    }
  }

  /** A buffer whose backing array can be scanned in place, without copying. */
  private static final class Buffer extends ByteArrayOutputStream {
    private byte[] array() {
      return buf;
    }

    private int length() {
      return count;
    }

    /**
     * Hands over the bytes received and lets go of the growth array, so the
     * parse does not run with the message held twice. When the array is
     * already exactly full it is handed over as is, with no copy at all.
     */
    private byte[] take() {
      byte[] bytes = count == buf.length ? buf : Arrays.copyOf(buf, count);
      buf = new byte[0];
      count = 0;
      return bytes;
    }
  }

  /**
   * One ParseEmail call. Accumulates chunks, sniffs the format from the
   * bytes as they arrive, and emits the envelope the moment the header block
   * is complete.
   *
   * <p>A call holds a parse slot from the moment its options arrive until it
   * ends, and no chunk is read before the slot is held. Through a transport,
   * flow control does the waiting: the next message is requested only once
   * the call is admitted and each chunk has been taken in, so a call queued
   * behind a busy server holds no buffer at all and its bytes stay in the
   * client's own send window. A cancelled call (the client gave up, or its
   * deadline passed) stops at the parser's next checkpoint and gives its slot
   * back.
   */
  private final class Upload implements StreamObserver<ParseEmailRequest> {

    private final StreamObserver<ParseEmailResponse> responses;
    /** The same observer when a transport drives the call; null when driven directly. */
    private final ServerCallStreamObserver<ParseEmailResponse> call;
    private final Buffer buffer = new Buffer();
    private final AtomicBoolean holdsSlot = new AtomicBoolean();

    private ParseOptions options;
    private Sink sink;
    private long cap = maxDocumentBytes;
    private boolean sawComplete;
    private volatile boolean parseScheduled;
    private volatile boolean aborted;
    private volatile boolean cancelled;

    private int scanFrom;
    private boolean headerBlockFound;
    private boolean ole2;
    private boolean formatDecided;

    private Upload(StreamObserver<ParseEmailResponse> responses) {
      this.responses = responses;
      this.call = responses instanceof ServerCallStreamObserver<ParseEmailResponse> server
          ? server
          : null;
    }

    /** Wires cancellation and flow control; called before the call starts. */
    private Upload attach() {
      if (call != null) {
        call.setOnCancelHandler(this::cancel);
        call.disableAutoRequest();
        // The options message is read straight away; chunks wait for a slot.
        call.request(1);
      }
      return this;
    }

    @Override
    public void onNext(ParseEmailRequest request) {
      if (aborted || cancelled) {
        return;
      }
      try {
        switch (request.getPayloadCase()) {
          case OPTIONS -> onOptions(request.getOptions());
          case CHUNK -> onChunk(request.getChunk().getData(), request.getChunk().getComplete());
          case PAYLOAD_NOT_SET -> abort(Status.INVALID_ARGUMENT
              .withDescription("request message carries neither options nor a chunk"));
          default -> abort(Status.INVALID_ARGUMENT
              .withDescription("unrecognized request payload"));
        }
      } catch (Throwable failure) {
        // The envelope is projected on this thread as the header block
        // lands, so header parsing can fail here; the call still ends with
        // a status rather than whatever gRPC makes of an escaped throwable.
        fail(failure);
      }
    }

    private void onOptions(ParseEmailOptions wire) {
      if (options != null) {
        abort(Status.INVALID_ARGUMENT
            .withDescription("options may only be sent once, as the first message"));
        return;
      }
      long requested = wire.getMaxDocumentMib() * MIB;
      cap = requested > 0 ? Math.min(maxDocumentBytes, requested) : maxDocumentBytes;
      options = new ParseOptions(
          wire.getDocumentId(),
          wire.getFilename(),
          wire.getContentType(),
          listAttachments(wire),
          wire.getIncludeAttachmentBytes(),
          maxAttachmentBytes);
      sink = new Sink(responses, options, wire.getEmitDocument(), () -> cancelled);
      admit();
    }

    /**
     * Takes a parse slot before a single byte of the message is read. With
     * a transport the wait happens off the transport's threads, and the next
     * message is requested only once the slot is held. Driven directly there
     * is no flow control to lean on, so the slot is taken inline.
     */
    private void admit() {
      if (call == null) {
        try {
          parseSlots.acquire();
          holdsSlot.set(true);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          abort(Status.UNAVAILABLE.withDescription("server shutting down"));
        }
        return;
      }
      try {
        executor.execute(() -> {
          try {
            parseSlots.acquire();
          } catch (InterruptedException interrupted) {
            abort(Status.UNAVAILABLE.withDescription("server shutting down"));
            return;
          }
          holdsSlot.set(true);
          if (aborted || cancelled) {
            // Ended while it waited; the slot goes straight back.
            releaseSlot();
            return;
          }
          call.request(1);
        });
      } catch (RejectedExecutionException shuttingDown) {
        abort(Status.UNAVAILABLE.withDescription("server shutting down"));
      }
    }

    /** Gives the parse slot back, once, whichever path gets here first. */
    private void releaseSlot() {
      if (holdsSlot.compareAndSet(true, false)) {
        parseSlots.release();
      }
    }

    /**
     * The client cancelled, or its deadline passed. A parse already running
     * notices at its next checkpoint and releases the slot itself; otherwise
     * the slot is released here.
     */
    private void cancel() {
      cancelled = true;
      if (!parseScheduled) {
        releaseSlot();
      }
    }

    /** Asks the transport for the next message once this one is taken in. */
    private void requestNext() {
      if (call != null && !aborted && !cancelled) {
        call.request(1);
      }
    }

    /**
     * Resolves the three attachment knobs into the one question the sink
     * asks. Listing is on unless the client opted out, and an explicit
     * list_attachments still wins over that opt-out: a client that says both
     * "omit" and "list" is a client whose newer field was set by a default
     * and whose older field was set on purpose.
     */
    private static boolean listAttachments(ParseEmailOptions wire) {
      return !wire.getOmitAttachmentList() || wire.getListAttachments();
    }

    private void onChunk(ByteString data, boolean complete) {
      if (options == null) {
        abort(Status.INVALID_ARGUMENT
            .withDescription("first message on the stream must be ParseEmailOptions"));
        return;
      }
      if (buffer.length() + (long) data.size() > cap) {
        metrics.messageRejected();
        abort(Status.RESOURCE_EXHAUSTED
            .withDescription("message exceeds the " + cap + " byte cap"));
        return;
      }
      try {
        // Straight into the accumulating buffer; ByteArrayOutputStream
        // cannot actually raise the IOException the signature declares.
        data.writeTo(buffer);
      } catch (IOException impossible) {
        throw new UncheckedIOException(impossible);
      }
      if (complete) {
        sawComplete = true;
      }
      sniff();
      requestNext();
    }

    /**
     * Incremental format detection. Once the OLE2 signature or the end of
     * the header block is visible, the answer is known -- and for RFC 822
     * the envelope goes out immediately rather than waiting for the body.
     */
    private void sniff() {
      if (formatDecided) {
        return;
      }
      if (buffer.length() < EmailSniffer.ole2MagicLength()) {
        return;
      }
      if (EmailSniffer.isOle2(buffer.array(), buffer.length())) {
        ole2 = true;
        formatDecided = true;
        return;
      }
      int headerLength =
          EmailSniffer.headerBlockLength(buffer.array(), buffer.length(), scanFrom);
      if (headerLength < 0) {
        scanFrom = EmailSniffer.rescanFrom(buffer.length());
        return;
      }
      headerBlockFound = true;
      formatDecided = true;
      if (!EmailSniffer.looksLikeHeaderBlock(buffer.array(), headerLength)) {
        return;
      }
      sink.info(HeaderProjection.project(
          HeaderProjection.read(buffer.array(), headerLength),
          options.documentId(),
          EmailFormat.EMAIL_FORMAT_EML));
    }

    @Override
    public void onError(Throwable error) {
      // The client cancelled or the transport failed: nobody is listening.
      aborted = true;
      cancel();
    }

    @Override
    public void onCompleted() {
      if (aborted || cancelled) {
        return;
      }
      if (options == null) {
        metrics.messageRejected();
        abort(Status.INVALID_ARGUMENT
            .withDescription("stream closed before ParseEmailOptions was sent"));
        return;
      }
      if (buffer.length() == 0) {
        metrics.messageRejected();
        abort(Status.INVALID_ARGUMENT.withDescription("no message bytes received"));
        return;
      }
      if (!sawComplete) {
        metrics.messageRejected();
        abort(Status.INVALID_ARGUMENT
            .withDescription("stream ended without a chunk marked complete"));
        return;
      }
      byte[] bytes = buffer.take();
      metrics.bytesReceived(bytes.length);
      parseScheduled = true;
      try {
        executor.execute(() -> run(bytes));
      } catch (RejectedExecutionException shuttingDown) {
        parseScheduled = false;
        abort(Status.UNAVAILABLE.withDescription("server shutting down"));
      }
    }

    /** Parses on a virtual thread, holding the slot admission already took. */
    private void run(byte[] bytes) {
      try {
        sink.checkpoint();
        dispatch(bytes);
        // The trailer and the document are for a client that is still there.
        sink.checkpoint();
        sink.trailer(bytes);
        responses.onCompleted();
        metrics.messageParsed();
      } catch (ParseCancelledException gone) {
        // The client went away mid-parse; there is nobody left to answer.
      } catch (Throwable failure) {
        // Throwable, not Exception: a StackOverflowError or an
        // OutOfMemoryError escaping this virtual thread would leave the
        // call open until the client's deadline, holding nothing but still
        // never answered.
        fail(failure);
      } finally {
        releaseSlot();
      }
    }

    /**
     * Ends the call with the status a failure maps to, and counts it. Bad
     * input is the caller's to fix and is counted as rejected; anything
     * this server did not anticipate is INTERNAL.
     */
    private void fail(Throwable failure) {
      Status status = switch (failure) {
        case UnsupportedFormatException unsupported ->
            Status.UNIMPLEMENTED.withDescription(unsupported.getMessage());
        case InvalidEmailException invalid ->
            Status.INVALID_ARGUMENT.withDescription(invalid.getMessage());
        // The walk caps its own nesting, so this is a library recursing on
        // hostile structure: still the input's doing.
        case StackOverflowError tooDeep ->
            Status.INVALID_ARGUMENT.withDescription("message structure is nested too deeply to parse");
        case OutOfMemoryError exhausted -> Status.RESOURCE_EXHAUSTED.withDescription(
            "message needs more memory than this server can give one parse");
        default -> Status.INTERNAL.withDescription("parser fault: " + failure);
      };
      if (status.getCode() == Status.Code.INTERNAL) {
        metrics.messageFailed();
      } else {
        metrics.messageRejected();
      }
      abort(status);
    }

    /**
     * Routes on what the sniffer already decided. The .eml branch requires
     * an envelope to have gone out: if the header block never ended, or
     * never looked like mail, that is a bad input, not a body to walk.
     */
    private void dispatch(byte[] bytes) {
      if (ole2) {
        MsgParser.parse(bytes, options, sink);
        return;
      }
      if (sink.infoSent()) {
        EmlParser.parse(bytes, options, sink);
        return;
      }
      if (headerBlockFound) {
        throw new UnsupportedFormatException(
            "bytes are neither an RFC 822 message nor an Outlook .msg");
      }
      if (EmailSniffer.looksLikeHeaderBlock(bytes, bytes.length)) {
        throw new InvalidEmailException(
            "message ended inside the header block; no empty line terminated the headers");
      }
      throw new UnsupportedFormatException(
          "bytes are neither an RFC 822 message nor an Outlook .msg");
    }

    /**
     * Ends the call with an error status, once, and gives the slot back.
     * Synchronized because admission runs on its own thread and can fail
     * while a request callback is failing the same call.
     */
    private synchronized void abort(Status status) {
      if (aborted) {
        return;
      }
      aborted = true;
      try {
        responses.onError(status.asRuntimeException());
      } catch (RuntimeException alreadyClosed) {
        // The call ended underneath us (the client went away); there is
        // nobody left to tell, and nothing more to do.
      } finally {
        if (!parseScheduled) {
          releaseSlot();
        }
      }
    }
  }
}
