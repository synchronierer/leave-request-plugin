package de.igslandstuhl.database.freistellung;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/**
 * Disposable DEV-only contract runner. It loads the real Permission Manager
 * and Leave Request jars as sibling plugins against a synthetic database.
 */
public final class PermissionIntegrationRunner {
    private PermissionIntegrationRunner() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("usage: <core-jar> <permission-jar> <leave-jar>");
        Path root = Files.createTempDirectory("freistellung-permission-contract-");
        Path database = root.resolve("database");
        try {
            Class<?> applicationType = Class.forName("de.igslandstuhl.database.Application");
            Object application = applicationType.getConstructor(String[].class).newInstance((Object) new String[] {
                    "--test-environment", "true", "--database", database.toString()
            });
            Field instance = applicationType.getDeclaredField("instance");
            instance.setAccessible(true);
            instance.set(null, application);

            Method registerBuiltinPlugins = applicationType.getDeclaredMethod("registerBuiltinPlugins");
            registerBuiltinPlugins.setAccessible(true);
            registerBuiltinPlugins.invoke(null);

            Class<?> loaderType = Class.forName("de.igslandstuhl.database.plugins.PluginLoader");
            Object loader = loaderType.getMethod("getInstance").invoke(null);
            Path plugins = root.resolve("plugins");
            Files.createDirectories(plugins);
            Files.copy(Path.of(args[1]), plugins.resolve("permission-manager.jar"));
            Files.copy(Path.of(args[2]), plugins.resolve("freistellung-plugin.jar"));
            loaderType.getMethod("preloadBuiltinPlugins").invoke(loader);
            loaderType.getMethod("preloadAllPlugins", File.class).invoke(loader, plugins.toFile());

            Object server = Class.forName("de.igslandstuhl.database.server.Server").getMethod("getInstance").invoke(null);
            Object connection = server.getClass().getMethod("getConnection").invoke(server);
            connection.getClass().getMethod("createTables").invoke(connection);
            connection.getClass().getMethod("migrateTables").invoke(connection);
            Class<?> eventListener = Class.forName("de.igslandstuhl.database.events.EventListener");
            eventListener.getMethod("registerTypes").invoke(null);
            loaderType.getMethod("loadAllPreloadedPlugins").invoke(loader);

            Class<?> teacherType = Class.forName("de.igslandstuhl.database.api.Teacher");
            Object teacher = teacherType.getMethod("registerTeacher", String.class, String.class, String.class, String.class)
                    .invoke(null, "Synthetic", "Permission", "synthetic-permission@example.invalid", "Synthetic!Password9");

            Object permissionInfo = ((Iterable<?>) loaderType.getMethod("getPluginInfos").invoke(loader)).iterator().next();
            for (Object info : (Iterable<?>) loaderType.getMethod("getPluginInfos").invoke(loader)) {
                Method description = info.getClass().getDeclaredMethod("description");
                description.setAccessible(true);
                Object pluginDescription = description.invoke(info);
                if ("permission-manager".equals(pluginDescription.getClass().getMethod("id").invoke(pluginDescription))) permissionInfo = info;
            }
            Method pluginClassLoader = permissionInfo.getClass().getDeclaredMethod("classLoader");
            pluginClassLoader.setAccessible(true);
            ClassLoader permissionLoader = (ClassLoader) pluginClassLoader.invoke(permissionInfo);
            Class<?> permissionType = Class.forName("de.igslandstuhl.database.permissions.Permission", true, permissionLoader);
            Class<?> nodeType = Class.forName("de.igslandstuhl.database.permissions.PermissionNode", true, permissionLoader);
            Method getPermission = permissionType.getMethod("getByName", String.class);
            Method getNode = nodeType.getMethod("getPermissionNode", String.class, permissionType);
            Method setActive = nodeType.getMethod("setActive", boolean.class);
            Method registerAll = Class.forName("de.igslandstuhl.database.permissions.UserEffect", true, permissionLoader).getMethod("registerAll");

            Object apply = getPermission.invoke(null, "freistellung_apply");
            Object orga = getPermission.invoke(null, "freistellung_orga");
            Object sl = getPermission.invoke(null, "freistellung_sl");
            setActive.invoke(getNode.invoke(null, teacherType.getMethod("getUsername").invoke(teacher), apply), true);
            setActive.invoke(getNode.invoke(null, teacherType.getMethod("getUsername").invoke(teacher), orga), false);
            setActive.invoke(getNode.invoke(null, teacherType.getMethod("getUsername").invoke(teacher), sl), false);
            registerAll.invoke(null);

            Object plugin = Class.forName("de.igslandstuhl.database.Registry").getMethod("pluginRegistry").invoke(null);
            Object leave = plugin.getClass().getMethod("get", Object.class).invoke(plugin, "freistellung");
            Method hasPermission = leave.getClass().getDeclaredMethod("hasPermission", Class.forName("de.igslandstuhl.database.api.User"), String.class);
            hasPermission.setAccessible(true);
            assertRights(hasPermission, leave, teacher, true, false, false, "submit-only");

            setActive.invoke(getNode.invoke(null, teacherType.getMethod("getUsername").invoke(teacher), orga), true);
            registerAll.invoke(null);
            assertRights(hasPermission, leave, teacher, true, true, false, "orga");

            setActive.invoke(getNode.invoke(null, teacherType.getMethod("getUsername").invoke(teacher), orga), false);
            setActive.invoke(getNode.invoke(null, teacherType.getMethod("getUsername").invoke(teacher), sl), true);
            registerAll.invoke(null);
            assertRights(hasPermission, leave, teacher, true, false, true, "sl");

            setActive.invoke(getNode.invoke(null, teacherType.getMethod("getUsername").invoke(teacher), sl), false);
            registerAll.invoke(null);
            assertRights(hasPermission, leave, teacher, true, false, false, "right-removal");
            System.out.println("PERMISSION_LEAVE_INTEGRATION PASS");
        } finally {
            try (var paths = Files.walk(root)) { paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete()); }
        }
        System.exit(0);
    }

    private static void assertRights(Method method, Object plugin, Object user, boolean apply, boolean orga, boolean sl, String caseName) throws Exception {
        boolean actualApply = (boolean) method.invoke(plugin, user, "freistellung_apply");
        boolean actualOrga = (boolean) method.invoke(plugin, user, "freistellung_orga");
        boolean actualSl = (boolean) method.invoke(plugin, user, "freistellung_sl");
        if (actualApply != apply || actualOrga != orga || actualSl != sl)
            throw new AssertionError(caseName + " expected=" + apply + "/" + orga + "/" + sl + " actual=" + actualApply + "/" + actualOrga + "/" + actualSl);
    }
}
