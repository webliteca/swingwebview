/**
 *
 * MIT License
 *
 * Copyright (c) 2019 Steve Hannah
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package ca.weblite.webview;


import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Encapsulates a WebBrowser window.  This class interfaces directly with the
 * ZSerge WebView via JNI and loads the webview in the current process.
 * Running in-process owns the main thread and its own event loop, so this
 * class is not suitable for use inside an existing Swing/JavaFX UI; use
 * {@link ca.weblite.webview.swing.WebViewComponent} for that.
 * @author shannah
 */
public class WebView {
    /**
     * The native pointer reference.
     */
    private long peer;
    
    
    private int w=800, h=600;
    private boolean resizable=true;
    private boolean fullscreen;
    private String title="Browser";
    private String url="https://weblite.ca";
    private List<String> onBeforeLoad = new ArrayList<String>();
    private Map<String,JavascriptCallback> bindings = new HashMap<String,JavascriptCallback>();

    /**
     * Make the webview window resizable.
     * @param b True to make the webview window resizable.
     * @return 
     */
    public WebView resizable(boolean b) {
        resizable = b;
        return this;
    }
    
    /**
     * Interface for callbacks from javascript.
     */
    public static interface JavascriptCallback {
        
        /**
         * Run a callback from Javascript.  
         * @param arg 
         */
        public void run(String arg);
    }
    
    /**
     * Add Javascript code to be run in the webview when each page loads.
     * @param js
     * @return 
     */
    public WebView addOnBeforeLoad(String js) {
        if (peer != 0) {
            WebViewNative.webview_init(peer, js);
        } else {
            onBeforeLoad.add(js);
        }
        return this;
    }
    
    
    /**
     * Creates a new webview.
     */
    public WebView() {
        
    }
    
    /**
     * Sets the URL of the webview.
     * @param url The url.
     * @return 
     */
    public WebView url(String url) {
        this.url = url;
        if (peer != 0) {
            WebViewNative.webview_navigate(peer, url);
        }
        return this;
    }
    
    /**
     * Gets the webview url.
     * @return 
     */
    public String url() {
        return url;
    }
    
    /**
     * Set the window title.
     * @param title
     * @return 
     */
    public WebView title(String title) {
        this.title = title;
        if (peer != 0) {
            WebViewNative.webview_set_title(peer, title);
        }
        return this;
    }
   
  
    /**
     * Set the window size.
     * @param w
     * @param h
     * @return 
     */
    public WebView size(int w, int h) {
        this.w = w;
        this.h = h;
        return this;
    }
    
    /**
     * Get the window width.
     * @return 
     */
    public int w() {
        return w;
    }
    
    /**
     * Get the window height.
     * @return 
     */
    public int h() {
        return h;
    }
    
    /**
     * Heap used so that callbacks don't get garbage collected by JVM while waiting
     * to be called by native code.
     */
    private ArrayList heap = new ArrayList();
    
    /**
     * Adds a javascript callback function.  This function will be accessible in Javascript
     * via window.name.
     * @param name THe name of the callback.
     * @param callback The callback to be run.
     * @return 
     */
    public WebView addJavascriptCallback(String name, JavascriptCallback callback) {
        if (peer == 0) {
            bindings.put(name, callback);
        } else {
            bindings.put(name, callback);
            WebViewNativeCallback fn = new WebViewNativeCallback() {
                @Override
                public void invoke(String arg2, long wv) {
                    JavascriptCallback cb = bindings.get(name);
                    if (cb != null) {
                        cb.run(arg2);
                    }
                }
            };
            heap.add(fn);
            WebViewNative.webview_bind(peer, name, fn, peer);
        }
        return this;
    }
    
    /**
     * Execute javascript.
     * @param js Javscript to run
     * @return 
     */
    public WebView eval(String js) {
        WebViewNative.webview_eval(peer, js);
        return this;
    }
    
    
   
    /**
     * Dispatch on the WebView event thread.
     * @param r 
     * @return 
     */
    public WebView dispatch(Runnable r) {
        heap.add(r);
        WebViewNative.webview_dispatch(peer, () -> {
            r.run();
            heap.remove(r);
        }, 0);
        return this;
    }
    
    
    /**
     * Shows the webview.  This will start the webview event loop, and it will
     * block execution.
     */
    public void show() {
        peer = WebViewNative.webview_create(0, 0);
        WebViewNative.webview_set_bounds(peer, 0, 0, w, h, 0);
        for (String js : onBeforeLoad) {
            WebViewNative.webview_init(peer, js);
        }
        WebViewNative.webview_set_title(peer, title);
        for (final String key : bindings.keySet()) {
            WebViewNativeCallback fn = (String arg2, long wv) -> {
                JavascriptCallback cb = bindings.get(key);
                if (cb != null) {
                    cb.run(arg2);
                }
            };
            heap.add(fn);
            WebViewNative.webview_bind(peer, key, fn, peer);
        }
        WebViewNative.webview_navigate(peer, url);
        WebViewNative.webview_run(peer);
    }

}
