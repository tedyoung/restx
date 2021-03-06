package restx.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import restx.common.RestxConfig;
import restx.config.Settings;
import restx.config.SettingsKey;
import restx.factory.AutoStartable;
import restx.factory.Module;
import restx.factory.Provides;

import javax.inject.Named;

/**
 * Date: 17/11/13
 * Time: 17:55
 */
@Module
public class SecurityModule {
    private static final Logger logger = LoggerFactory.getLogger(SecurityModule.class);

    @Settings
    public static interface SecuritySettings {
        @SettingsKey(key = "restx.sessions.stats.limit", defaultValue = "100",
                doc = "The maximum number of sessions data to keep in memory for statistics in the monitor view")
        int sessionsLimit();

    }

    // @Settings is not processed as long as this is part of core itself
    @Provides
    public SecuritySettings securitySettings(final RestxConfig config) {
        return new SecuritySettings() {
            @Override
            public int sessionsLimit() {
                return config.getInt("restx.sessions.stats.limit").or(100);
            }
        };
    }

    @Provides @Named("Sessions")
    public Sessions session(SecuritySettings settings) {
        return new Sessions(settings.sessionsLimit());
    }

    // this makes Sessions part of the auto started components, thus making it server wide.
    @Provides
    public AutoStartable startSessions(@Named("Sessions") Sessions sessions) {
        return new AutoStartable() {
            @Override
            public void start() {
                logger.debug("starting sessions statistics");
            }
        };
    }
}
