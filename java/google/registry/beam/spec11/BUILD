package(
    default_visibility = ["//visibility:public"],
)

licenses(["notice"])  # Apache 2.0

java_library(
    name = "spec11",
    srcs = glob(["*.java"]),
    resources = glob(["sql/*"]),
    deps = [
        "//java/google/registry/beam",
        "//java/google/registry/config",
        "//java/google/registry/util",
        "@com_google_auto_value",
        "@com_google_dagger",
        "@com_google_flogger",
        "@com_google_flogger_system_backend",
        "@com_google_guava",
        "@javax_inject",
        "@joda_time",
        "@org_apache_avro",
        "@org_apache_beam_runners_direct_java",
        "@org_apache_beam_runners_google_cloud_dataflow_java",
        "@org_apache_beam_sdks_java_core",
        "@org_apache_beam_sdks_java_io_google_cloud_platform",
        "@org_apache_httpcomponents_httpclient",
        "@org_apache_httpcomponents_httpcore",
        "@org_json",
    ],
)
