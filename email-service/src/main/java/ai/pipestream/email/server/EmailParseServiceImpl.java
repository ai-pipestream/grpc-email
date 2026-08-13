package ai.pipestream.email.server;

import ai.pipestream.email.parse.EmailSniffer;
import ai.pipestream.email.parse.EmlParser;
import ai.pipestream.email.parse.HeaderProjection;
import ai.pipestream.email.parse.InvalidEmailException;
import ai.pipestream.email.parse.MsgParser;
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
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
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

  private final long maxDocumentBytes;
  private final long maxAttachmentBytes;
  private final int maxConcurrentParses;
  private final Semaphore parseSlots;
  private final ExecutorService executor;

  final AtomicLong parsed = new AtomicLong();
  final AtomicLong rejected = new AtomicLong();
  final AtomicLong failed = new AtomicLong();
  final AtomicLong bodyPartsEmitted = new AtomicLong();
  final AtomicLong attachmentsSeen = new AtomicLong();
  final AtomicLong bytesRead = new AtomicLong();

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

  @Override
  public StreamObserver<ParseEmailRequest> parseEmail(
      StreamObserver<ParseEmailResponse> responses) {
    return new Upload(responses);
  }

  @Override
  public void getServiceInfo(
      GetServiceInfoRequest request, StreamObserver<GetServiceInfoResponse> responses) {
    responses.onNext(
        GetServiceInfoResponse.newBuilder()
            .setServiceVersion(SERVICE_VERSION)
            .setApiVersion(API_VERSION)
            .setMailVersion(mailVersion())
            .setPoiVersion(org.apache.poi.Version.getVersion())
            .addSupportedFormats(EmailFormat.EMAIL_FORMAT_EML)
            .addSupportedFormats(EmailFormat.EMAIL_FORMAT_MSG)
            .setMaxDocumentBytes(maxDocumentBytes)
            .setMaxAttachmentBytes(maxAttachmentBytes)
            .setMaxConcurrentParses(maxConcurrentParses)
            .build());
    responses.onCompleted();
  }

  /**
   * Jakarta Mail version, read from the manifest of the jar that provides
   * it. The jar ships an OSGi Bundle-Version rather than the
   * Implementation-Version {@code Package} exposes, so the manifest is read
   * directly; outside a jar there is nothing to report.
   */
  private static String mailVersion() {
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
   */
  private final class Sink implements ParseSink {

    private final StreamObserver<ParseEmailResponse> responses;
    private final ParseOptions options;
    private final List<String> warnings = new ArrayList<>();
    private boolean infoSent;
    private int bodyParts;
    private int attachments;
    private long attachmentBytes;

    private Sink(StreamObserver<ParseEmailResponse> responses, ParseOptions options) {
      this.responses = responses;
      this.options = options;
    }

    @Override
    public synchronized void info(EmailInfo info) {
      if (infoSent) {
        return;
      }
      infoSent = true;
      responses.onNext(ParseEmailResponse.newBuilder().setEmailInfo(info).build());
    }

    @Override
    public synchronized void bodyPart(BodyPart part) {
      bodyParts++;
      responses.onNext(ParseEmailResponse.newBuilder().setBodyPart(part).build());
    }

    @Override
    public synchronized void attachment(Attachment attachment) {
      attachments++;
      attachmentBytes += attachment.getSizeBytes();
      if (options.emitAttachments()) {
        responses.onNext(ParseEmailResponse.newBuilder().setAttachment(attachment).build());
      }
    }

    @Override
    public synchronized void warn(String warning) {
      warnings.add(warning);
    }

    private synchronized boolean infoSent() {
      return infoSent;
    }

    private synchronized void trailer(long messageBytes) {
      ParseStatus.Builder status = ParseStatus.newBuilder()
          .setState(warnings.isEmpty() ? ParseStatus.State.STATE_OK
              : ParseStatus.State.STATE_PARTIAL)
          .addAllWarnings(warnings)
          .setBodyParts(bodyParts)
          .setAttachments(attachments)
          .setAttachmentBytes(attachmentBytes)
          .setMessageBytes(messageBytes);
      responses.onNext(ParseEmailResponse.newBuilder().setStatus(status).build());
      bodyPartsEmitted.addAndGet(bodyParts);
      attachmentsSeen.addAndGet(attachments);
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
  }

  /**
   * One ParseEmail call. Accumulates chunks, sniffs the format from the
   * bytes as they arrive, and emits the envelope the moment the header block
   * is complete.
   */
  private final class Upload implements StreamObserver<ParseEmailRequest> {

    private final StreamObserver<ParseEmailResponse> responses;
    private final Buffer buffer = new Buffer();

    private ParseOptions options;
    private Sink sink;
    private long cap = maxDocumentBytes;
    private boolean sawComplete;
    private boolean aborted;

    private int scanFrom;
    private boolean headerBlockFound;
    private boolean ole2;
    private boolean formatDecided;

    private Upload(StreamObserver<ParseEmailResponse> responses) {
      this.responses = responses;
    }

    @Override
    public void onNext(ParseEmailRequest request) {
      if (aborted) {
        return;
      }
      switch (request.getPayloadCase()) {
        case OPTIONS -> onOptions(request.getOptions());
        case CHUNK -> onChunk(request.getChunk().getData().toByteArray(),
            request.getChunk().getComplete());
        case PAYLOAD_NOT_SET -> abort(Status.INVALID_ARGUMENT
            .withDescription("request message carries neither options nor a chunk"));
        default -> abort(Status.INVALID_ARGUMENT
            .withDescription("unrecognized request payload"));
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
          wire.getListAttachments(),
          wire.getIncludeAttachmentBytes(),
          maxAttachmentBytes);
      sink = new Sink(responses, options);
    }

    private void onChunk(byte[] data, boolean complete) {
      if (options == null) {
        abort(Status.INVALID_ARGUMENT
            .withDescription("first message on the stream must be ParseEmailOptions"));
        return;
      }
      if (buffer.length() + (long) data.length > cap) {
        rejected.incrementAndGet();
        abort(Status.RESOURCE_EXHAUSTED
            .withDescription("message exceeds the " + cap + " byte cap"));
        return;
      }
      buffer.write(data, 0, data.length);
      if (complete) {
        sawComplete = true;
      }
      sniff();
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
      aborted = true;
    }

    @Override
    public void onCompleted() {
      if (aborted) {
        return;
      }
      if (options == null) {
        rejected.incrementAndGet();
        abort(Status.INVALID_ARGUMENT
            .withDescription("stream closed before ParseEmailOptions was sent"));
        return;
      }
      if (buffer.length() == 0) {
        rejected.incrementAndGet();
        abort(Status.INVALID_ARGUMENT.withDescription("no message bytes received"));
        return;
      }
      if (!sawComplete) {
        rejected.incrementAndGet();
        abort(Status.INVALID_ARGUMENT
            .withDescription("stream ended without a chunk marked complete"));
        return;
      }
      byte[] bytes = buffer.toByteArray();
      bytesRead.addAndGet(bytes.length);
      executor.execute(() -> run(bytes));
    }

    private void run(byte[] bytes) {
      try {
        parseSlots.acquire();
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        abort(Status.UNAVAILABLE.withDescription("server shutting down"));
        return;
      }
      try {
        dispatch(bytes);
        sink.trailer(bytes.length);
        responses.onCompleted();
        parsed.incrementAndGet();
      } catch (UnsupportedFormatException unsupported) {
        rejected.incrementAndGet();
        abort(Status.UNIMPLEMENTED.withDescription(unsupported.getMessage()));
      } catch (InvalidEmailException invalid) {
        rejected.incrementAndGet();
        abort(Status.INVALID_ARGUMENT.withDescription(invalid.getMessage()));
      } catch (Exception fault) {
        failed.incrementAndGet();
        abort(Status.INTERNAL.withDescription("parser fault: " + fault));
      } finally {
        parseSlots.release();
      }
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
      if (EmailSniffer.looksLikeHeaderBlock(buffer.array(), buffer.length())) {
        throw new InvalidEmailException(
            "message ended inside the header block; no empty line terminated the headers");
      }
      throw new UnsupportedFormatException(
          "bytes are neither an RFC 822 message nor an Outlook .msg");
    }

    private void abort(Status status) {
      if (aborted) {
        return;
      }
      aborted = true;
      responses.onError(status.asRuntimeException());
    }
  }
}
