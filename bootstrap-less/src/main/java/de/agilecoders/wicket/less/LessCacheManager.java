package de.agilecoders.wicket.less;

import com.github.sommeri.less4j.Less4jException;
import com.github.sommeri.less4j.LessCompiler;
import com.github.sommeri.less4j.LessSource;
import com.github.sommeri.less4j.core.ThreadUnsafeLessCompiler;
import org.apache.wicket.Application;
import org.apache.wicket.MetaDataKey;
import org.apache.wicket.WicketRuntimeException;
import org.apache.wicket.util.io.Connections;
import org.apache.wicket.util.time.Time;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URL;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * A class that manages the generated CSS content for Less resources.
 */
public class LessCacheManager {

    private static final Logger LOG = LoggerFactory.getLogger(LessCacheManager.class);

    private static final MetaDataKey<LessCacheManager> KEY = new MetaDataKey<LessCacheManager>() {
    };

    /**
     * A cache that keeps the root LessSource.URLSource instance per URL.
     * Each root LessSource keeps references to all imported LessSource's in it.
     */
    private final ConcurrentMap<URL, LessSource.URLSource> urlSourceCache =
            new ConcurrentHashMap<URL, LessSource.URLSource>();

    /**
     * A cache that keeps the generated CSS content per root LessSource
     */
    private final ConcurrentMap<LessSource.URLSource, ConcurrentMap<Time, String>> contentCache =
            new ConcurrentHashMap<LessSource.URLSource, ConcurrentMap<Time, String>>();

    /**
     * Returns the LessSource.URLSource per URL.
     * If there is no entry in the cache then it will be automatically registered
     *
     * @param lessUrl the URL to the Less resource file
     * @return The LessSource for the Less resource file
     */
    public LessSource.URLSource getLessSource(URL lessUrl) {

        LessSource.URLSource lessSource = new LessSource.URLSource(lessUrl);
        LessSource.URLSource oldValue = urlSourceCache.putIfAbsent(lessUrl, lessSource);
        if (oldValue != null) {
            lessSource = oldValue;
        }

        return lessSource;
    }

    /**
     * Returns the generated CSS content per Less resource.
     * If there is no cached content or the root LessSource or any of its imported resources
     * is updated then the CSS content is (re-)generated
     *
     * @param lessSource The root LessSource for which to load its CSS representation
     * @return The generated CSS content
     */
    public String getCss(LessSource.URLSource lessSource) {

        ConcurrentMap<Time, String> timeToContentMap = contentCache.get(lessSource);
        if (timeToContentMap == null) {
            timeToContentMap = new ConcurrentHashMap<Time, String>();
            ConcurrentMap<Time, String> old = contentCache.putIfAbsent(lessSource, timeToContentMap);
            if (old != null) {
                timeToContentMap = old;
            }
        }

        Time lastModifiedTime = getLastModifiedTime(lessSource);
        String cssContent = timeToContentMap.get(lastModifiedTime);

        if (cssContent == null) {

            // clear any obsolete content
            timeToContentMap.clear();

            ThreadUnsafeLessCompiler compiler = new ThreadUnsafeLessCompiler();

            try {
                LessCompiler.CompilationResult result = compiler.compile(lessSource);
                List<LessCompiler.Problem> warnings = result.getWarnings();

                for (LessCompiler.Problem warning : warnings) {
                    LOG.warn("There is a warning during compilation of '{}' at line {}, character {}. Message: {}",
                             new Object[] {lessSource.getInputURL(), warning.getLine(), warning.getCharacter(), warning.getMessage()});
                }

                cssContent = result.getCss();

                timeToContentMap.put(lastModifiedTime, cssContent);

            } catch (Less4jException x) {
                throw new WicketRuntimeException("An error occurred while compiling Less resource " + lessSource.getInputURL().toExternalForm(), x);
            }
        }

        return cssContent;
    }

    /**
     * @param lessSource The root LessSource which last modification time should be calculated
     * @return The time when either the root LessSource or any of the imported resources has been last modified
     */
    public Time getLastModifiedTime(LessSource.URLSource lessSource) {
        Time modified = Time.START_OF_UNIX_TIME;
        return findLastModified(lessSource, modified);
    }

    /**
     * Calculates the soonest time when a LessSource or any of its imported resources
     * has been modified
     *
     * @param source The LessSource which time to check
     * @param time The last modification time of the parent resource
     * @return The latest modified time of the root LessSource and its imported resources
     */
    private Time findLastModified(LessSource.URLSource source, Time time) {
        Time max = time;
        try {
            Time lastModified = Connections.getLastModified(source.getInputURL());
            max = Time.maxNullSafe(time, lastModified);

            Collection<LessSource> importedSources = source.getImportedSources();
            if (importedSources != null) {
                for (LessSource importedSource : importedSources) {
                    max = findLastModified((LessSource.URLSource) importedSource, max);
                }
            }
        } catch (IOException iox) {
            LOG.warn("Cannot read the last modification time of a resource " + source.getInputURL().toExternalForm(), iox);
        }
        return max;
    }

    /**
     * Registers this instance as the one which should be used in this application.
     * @param app The application used as a scope
     * @see #get()
     */
    public void install(Application app) {
        app.setMetaData(KEY, this);
    }

    /**
     * @return the registered instance of this manager during the start up of the application
     */
    public static LessCacheManager get() {
        if (Application.exists()) {
            return get(Application.get());
        }

        throw new IllegalStateException("there is no active application assigned to this thread.");
    }

    /**
     * @param application the application that keeps the cache manage
     * @return The registered instance of this manager during the start up of the application
     */
    private static LessCacheManager get(Application application) {
        return application.getMetaData(KEY);
    }
}
