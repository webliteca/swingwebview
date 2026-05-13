/*
 * MIT License
 *
 * Demonstrates embedding a native WebView as a heavyweight Swing component.
 */
package ca.weblite.webview.demos;

import ca.weblite.webview.swing.WebViewHeavyweightComponent;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.EventQueue;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;

public class WebViewHeavyweightDemo {

    public static void main(String[] args) {
        EventQueue.invokeLater(WebViewHeavyweightDemo::run);
    }

    private static void run() {
        JFrame frame = new JFrame("WebView Heavyweight Demo");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        WebViewHeavyweightComponent wv = new WebViewHeavyweightComponent();
        wv.setUrl("https://example.com");
        wv.setPreferredSize(new Dimension(900, 600));

        JTextField urlField = new JTextField("https://example.com", 60);
        JButton go = new JButton("Go");
        go.addActionListener(e -> wv.setUrl(urlField.getText().trim()));
        urlField.addActionListener(e -> wv.setUrl(urlField.getText().trim()));

        JPanel top = new JPanel(new BorderLayout(4, 4));
        top.add(new JLabel(" URL: "), BorderLayout.WEST);
        top.add(urlField, BorderLayout.CENTER);
        top.add(go, BorderLayout.EAST);

        frame.getContentPane().setLayout(new BorderLayout());
        frame.getContentPane().add(top, BorderLayout.NORTH);
        frame.getContentPane().add(wv, BorderLayout.CENTER);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }
}
