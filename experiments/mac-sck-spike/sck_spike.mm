// sck_spike.mm — exploratory spike for ScreenCaptureKit-backed
// offscreen WKWebView capture on macOS.
//
// Goal: validate three things before investing in a full SPDD-driven
// implementation of the macOS lightweight WebView path
// (spdd/prompt/7-...-Lightweight-Webview-Embedding.md, currently a stub
// on macOS / Windows):
//
//   1. Does SCStream see an NSWindow positioned far off-screen at
//      (-32000, -32000) once we orderFront: it? (i.e. is the
//      "offscreen but in WindowServer's window list" pattern viable?)
//   2. Do the BGRA pixels SCK delivers actually reflect the
//      WKWebView's painted content, or are they blank/white the way
//      CARenderer is for WKWebView?
//   3. What FPS does SCK sustain at 1024x768 with minimumFrameInterval
//      set to 60Hz? Anything sustained at >=25 FPS is enough to justify
//      proceeding; <10 FPS would push us back toward takeSnapshot.
//
// Build:   ./build.sh
// Run:     ./sck_spike [url]
//          (default URL: https://example.com)
//
// Exit code 0 = pass; non-zero codes documented in the verdict block
// at the bottom of main(). On pass, a PNG of frame 30 is written to
// $TMPDIR/sck_spike_frame30.png — open that to confirm pixels are real.

#import <Cocoa/Cocoa.h>
#import <WebKit/WebKit.h>
#import <ScreenCaptureKit/ScreenCaptureKit.h>
#import <CoreMedia/CoreMedia.h>
#import <CoreVideo/CoreVideo.h>
#import <ImageIO/ImageIO.h>
#import <UniformTypeIdentifiers/UniformTypeIdentifiers.h>
#include <stdio.h>
#include <mach/mach_time.h>

static const int kWidth  = 1024;
static const int kHeight = 768;

@interface FrameSink : NSObject <SCStreamOutput, SCStreamDelegate>
@property (atomic) int frameCount;
@property (atomic) uint64_t firstFrameMachTime;
@property (atomic) uint64_t lastFrameMachTime;
@property (atomic) BOOL sawNonBlankFrame;
@property (atomic, strong) NSString *snapshotPath;
@end

@implementation FrameSink

- (void)stream:(SCStream *)stream
    didOutputSampleBuffer:(CMSampleBufferRef)sampleBuffer
                   ofType:(SCStreamOutputType)type {
    if (type != SCStreamOutputTypeScreen) return;
    if (!CMSampleBufferIsValid(sampleBuffer)) return;

    // SCK marks idle/repeat frames with non-Complete status; skip those
    // so the frame counter reflects fresh paints.
    CFArrayRef attachments =
        CMSampleBufferGetSampleAttachmentsArray(sampleBuffer, false);
    if (attachments && CFArrayGetCount(attachments) > 0) {
        CFDictionaryRef attach =
            (CFDictionaryRef)CFArrayGetValueAtIndex(attachments, 0);
        CFNumberRef statusNum = (CFNumberRef)CFDictionaryGetValue(
            attach, (__bridge CFStringRef)SCStreamFrameInfoStatus);
        int status = 0; // SCFrameStatusComplete
        if (statusNum) {
            CFNumberGetValue(statusNum, kCFNumberSInt32Type, &status);
        }
        if (status != 0) return;
    }

    CVPixelBufferRef pb = CMSampleBufferGetImageBuffer(sampleBuffer);
    if (!pb) return;

    int n = ++self.frameCount;
    uint64_t now = mach_absolute_time();
    if (self.firstFrameMachTime == 0) self.firstFrameMachTime = now;
    self.lastFrameMachTime = now;

    CVPixelBufferLockBaseAddress(pb, kCVPixelBufferLock_ReadOnly);
    size_t w = CVPixelBufferGetWidth(pb);
    size_t h = CVPixelBufferGetHeight(pb);
    size_t rowBytes = CVPixelBufferGetBytesPerRow(pb);
    uint8_t *base = (uint8_t *)CVPixelBufferGetBaseAddress(pb);

    // Cheap non-blank check: sample a stride across the buffer and look
    // for any byte that isn't 0xFF (white) and isn't 0x00 (cleared).
    // We sample rather than scanning everything to keep this lightweight
    // on the SCK callback thread.
    if (!self.sawNonBlankFrame && base) {
        BOOL allWhite = YES, allZero = YES;
        for (size_t y = 0; y < h; y += 16) {
            uint8_t *row = base + y * rowBytes;
            for (size_t x = 0; x < rowBytes && x < 4096; x += 16) {
                if (row[x] != 0xFF) allWhite = NO;
                if (row[x] != 0x00) allZero = NO;
                if (!allWhite && !allZero) break;
            }
            if (!allWhite && !allZero) break;
        }
        if (!allWhite && !allZero) self.sawNonBlankFrame = YES;
    }

    // Save frame 30 as a PNG so the operator can eyeball whether it's
    // really the WKWebView content.
    if (n == 30 && !self.snapshotPath) {
        CGColorSpaceRef cs = CGColorSpaceCreateDeviceRGB();
        CGContextRef ctx = CGBitmapContextCreate(
            base, w, h, 8, rowBytes, cs,
            kCGImageAlphaPremultipliedFirst | kCGBitmapByteOrder32Little);
        if (ctx) {
            CGImageRef cg = CGBitmapContextCreateImage(ctx);
            if (cg) {
                NSString *path = [NSTemporaryDirectory()
                    stringByAppendingPathComponent:@"sck_spike_frame30.png"];
                NSURL *url = [NSURL fileURLWithPath:path];
                CGImageDestinationRef dest =
                    CGImageDestinationCreateWithURL(
                        (__bridge CFURLRef)url,
                        (__bridge CFStringRef)UTTypePNG.identifier,
                        1, NULL);
                if (dest) {
                    CGImageDestinationAddImage(dest, cg, NULL);
                    CGImageDestinationFinalize(dest);
                    CFRelease(dest);
                    self.snapshotPath = path;
                }
                CGImageRelease(cg);
            }
            CGContextRelease(ctx);
        }
        CGColorSpaceRelease(cs);
    }
    CVPixelBufferUnlockBaseAddress(pb, kCVPixelBufferLock_ReadOnly);
}

- (void)stream:(SCStream *)stream didStopWithError:(NSError *)error {
    if (error) {
        fprintf(stderr, "[sck] stream stopped with error: %s\n",
            error.localizedDescription.UTF8String);
    }
}

@end

static double mach_to_seconds(uint64_t t) {
    static mach_timebase_info_data_t tb;
    if (tb.denom == 0) mach_timebase_info(&tb);
    return (double)t * tb.numer / tb.denom / 1e9;
}

int main(int argc, const char *argv[]) {
    @autoreleasepool {
        NSString *urlString = argc > 1
            ? [NSString stringWithUTF8String:argv[1]]
            : @"https://example.com";
        NSURL *probeURL = [NSURL URLWithString:urlString];
        fprintf(stderr, "[sck] probing URL: %s\n",
            urlString.UTF8String);
        fprintf(stderr, "[sck] target size: %dx%d\n", kWidth, kHeight);

        [NSApplication sharedApplication];
        // Accessory: no Dock icon, no menu bar takeover, but still
        // gets a full NSApplication run loop so WKWebView can paint.
        [NSApp setActivationPolicy:NSApplicationActivationPolicyAccessory];

        // ----- Permission preflight ---------------------------------
        // CGPreflightScreenCaptureAccess() returns false without
        // prompting; CGRequestScreenCaptureAccess() shows the system
        // sheet but does not block. Either way the user must approve
        // and re-run, because permission is gated at process launch.
        if (!CGPreflightScreenCaptureAccess()) {
            fprintf(stderr,
                "[sck] Screen Recording permission NOT granted.\n"
                "[sck] Requesting now...\n");
            CGRequestScreenCaptureAccess();
            fprintf(stderr,
                "[sck] Approve in: System Settings > Privacy & "
                "Security > Screen & System Audio Recording, then "
                "re-run this binary.\n");
            return 2;
        }
        fprintf(stderr, "[sck] Screen Recording permission OK.\n");

        // ----- Offscreen NSWindow + WKWebView -----------------------
        NSRect frame = NSMakeRect(-32000, -32000, kWidth, kHeight);
        NSWindow *win = [[NSWindow alloc]
            initWithContentRect:frame
                      styleMask:NSWindowStyleMaskBorderless
                        backing:NSBackingStoreBuffered
                          defer:NO];
        win.releasedWhenClosed = NO;
        win.excludedFromWindowsMenu = YES;
        win.ignoresMouseEvents = YES;
        win.opaque = YES;
        win.backgroundColor = NSColor.whiteColor;
        win.title = @"sck-spike-offscreen";
        // Above-normal level so a maximized user window does not
        // shadow it in the window list; below screensaver so it does
        // not interfere with anything visible.
        win.level = NSScreenSaverWindowLevel - 1;

        WKWebView *wv = [[WKWebView alloc]
            initWithFrame:NSMakeRect(0, 0, kWidth, kHeight)];
        wv.autoresizingMask = NSViewWidthSizable | NSViewHeightSizable;
        [win.contentView addSubview:wv];

        // orderFront: is what registers the window with WindowServer
        // and assigns a windowNumber. Without this the window has no
        // CGWindowID for SCK to filter on.
        [win orderFront:nil];
        CGWindowID winID = (CGWindowID)win.windowNumber;
        fprintf(stderr, "[sck] offscreen NSWindow windowNumber=%u\n",
            winID);

        [wv loadRequest:[NSURLRequest requestWithURL:probeURL]];

        // ----- Find our window in SCShareableContent ----------------
        __block SCWindow *targetWindow = nil;
        __block NSUInteger totalWindows = 0;
        __block NSError *contentErr = nil;
        dispatch_semaphore_t sem = dispatch_semaphore_create(0);
        [SCShareableContent
            getShareableContentExcludingDesktopWindows:NO
                            onScreenWindowsOnly:NO
                              completionHandler:^(SCShareableContent *content, NSError *err) {
                contentErr = err;
                if (!err) {
                    totalWindows = content.windows.count;
                    for (SCWindow *w in content.windows) {
                        if (w.windowID == winID) {
                            targetWindow = w;
                            break;
                        }
                    }
                }
                dispatch_semaphore_signal(sem);
            }];
        dispatch_semaphore_wait(sem,
            dispatch_time(DISPATCH_TIME_NOW, 5 * NSEC_PER_SEC));

        if (contentErr) {
            fprintf(stderr, "[sck] getShareableContent error: %s\n",
                contentErr.localizedDescription.UTF8String);
            return 3;
        }
        fprintf(stderr,
            "[sck] SCShareableContent has %lu windows; target %s\n",
            (unsigned long)totalWindows,
            targetWindow ? "FOUND" : "NOT FOUND");
        if (!targetWindow) {
            fprintf(stderr,
                "[sck] FAIL: offscreen window is not in "
                "SCShareableContent. Probe #1 failed.\n");
            return 4;
        }

        // ----- Configure & start the stream -------------------------
        SCContentFilter *filter = [[SCContentFilter alloc]
            initWithDesktopIndependentWindow:targetWindow];
        SCStreamConfiguration *cfg = [SCStreamConfiguration new];
        cfg.width = kWidth;
        cfg.height = kHeight;
        cfg.pixelFormat = kCVPixelFormatType_32BGRA;
        cfg.showsCursor = NO;
        cfg.minimumFrameInterval = CMTimeMake(1, 60);
        cfg.queueDepth = 6;

        FrameSink *sink = [FrameSink new];
        SCStream *stream = [[SCStream alloc]
            initWithFilter:filter
             configuration:cfg
                  delegate:sink];

        dispatch_queue_t sckq = dispatch_queue_create(
            "sck.spike.output", DISPATCH_QUEUE_SERIAL);
        NSError *addErr = nil;
        if (![stream addStreamOutput:sink
                                type:SCStreamOutputTypeScreen
                  sampleHandlerQueue:sckq
                               error:&addErr]) {
            fprintf(stderr, "[sck] addStreamOutput failed: %s\n",
                addErr.localizedDescription.UTF8String);
            return 5;
        }

        __block NSError *startErr = nil;
        dispatch_semaphore_t startSem = dispatch_semaphore_create(0);
        [stream startCaptureWithCompletionHandler:^(NSError *err) {
            startErr = err;
            dispatch_semaphore_signal(startSem);
        }];
        dispatch_semaphore_wait(startSem,
            dispatch_time(DISPATCH_TIME_NOW, 5 * NSEC_PER_SEC));
        if (startErr) {
            fprintf(stderr, "[sck] startCapture failed: %s\n",
                startErr.localizedDescription.UTF8String);
            return 6;
        }
        fprintf(stderr,
            "[sck] capture started; pumping run loop ~3.0s...\n");

        // Pump the main run loop so WKWebView lays out and paints.
        // The SCK delegate runs on its own dispatch queue, but
        // WebKit needs the main run loop alive.
        NSDate *deadline =
            [NSDate dateWithTimeIntervalSinceNow:3.0];
        while ([deadline timeIntervalSinceNow] > 0) {
            [[NSRunLoop currentRunLoop]
                runMode:NSDefaultRunLoopMode
                beforeDate:[NSDate dateWithTimeIntervalSinceNow:0.05]];
        }

        dispatch_semaphore_t stopSem = dispatch_semaphore_create(0);
        [stream stopCaptureWithCompletionHandler:^(NSError *err) {
            (void)err;
            dispatch_semaphore_signal(stopSem);
        }];
        dispatch_semaphore_wait(stopSem,
            dispatch_time(DISPATCH_TIME_NOW, 2 * NSEC_PER_SEC));

        // ----- Report ------------------------------------------------
        int n = sink.frameCount;
        double elapsed = (n >= 2)
            ? mach_to_seconds(
                sink.lastFrameMachTime - sink.firstFrameMachTime)
            : 0;
        double fps = (elapsed > 0) ? (n - 1) / elapsed : 0;

        fprintf(stderr,
            "\n"
            "[sck] ============== RESULTS ==============\n"
            "[sck]  frames received:       %d\n"
            "[sck]  capture window (s):    %.3f\n"
            "[sck]  effective FPS:         %.1f\n"
            "[sck]  saw non-blank pixels:  %s\n"
            "[sck]  frame-30 PNG:          %s\n"
            "[sck] =====================================\n\n",
            n, elapsed, fps,
            sink.sawNonBlankFrame ? "YES" : "NO",
            sink.snapshotPath
                ? sink.snapshotPath.UTF8String
                : "(not captured)");

        if (n < 10) {
            fprintf(stderr,
                "[sck] VERDICT: FAIL — fewer than 10 frames in 3s. "
                "SCK is delivering very slowly or not at all.\n");
            return 7;
        }
        if (!sink.sawNonBlankFrame) {
            fprintf(stderr,
                "[sck] VERDICT: FAIL — frames arrived but pixels "
                "looked all-white or all-zero. SCK is not seeing "
                "WKWebView's painted content.\n");
            return 8;
        }
        if (fps < 25) {
            fprintf(stderr,
                "[sck] VERDICT: MARGINAL — capture works with real "
                "pixels but FPS is below 25.\n");
            return 9;
        }
        fprintf(stderr,
            "[sck] VERDICT: PASS — SCK captures offscreen WKWebView "
            "at >=25 FPS with real pixels. Open the PNG to confirm.\n");
        return 0;
    }
}
