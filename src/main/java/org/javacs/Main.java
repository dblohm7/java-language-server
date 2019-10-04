package org.javacs;

import java.util.Arrays;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.javacs.lsp.*;

public class Main {
    private static final Logger ROOT_LOG = Logger.getLogger("org.javacs");
    private static final Logger LOG = Logger.getLogger("org.javacs.main");

    public static void setRootFormat() {
        for (var h : ROOT_LOG.getHandlers()) h.setFormatter(new LogFormat());
    }

    public static void main(String[] args) {
        boolean quiet = Arrays.stream(args).anyMatch("--quiet"::equals);

        if (quiet) {
            LOG.setLevel(Level.OFF);
        }

        try {
            // Logger.getLogger("").addHandler(new FileHandler("javacs.%u.log", false));
            setRootFormat();

            LSP.connect(JavaLanguageServer::new, System.in, System.out);
        } catch (Throwable t) {
            LOG.log(Level.SEVERE, t.getMessage(), t);

            System.exit(1);
        }
    }
}
