# Reference R backend for the cloview bridge, protocol "cloview-bridge/1": renders plots
# to PNG/SVG files that a cloview view shows with :ui/image (cloview.bridge.rplot,
# docs/R_VISUALIZATION_INTEGRATION.md).
#
#   Rscript --vanilla backend.R [plots.R ...]      (default: plots.R next to this file)
#
# Packages: jsonlite (MIT, required). Used when installed: ggplot2 (MIT) for the
# example plots, ragg (MIT) for PNG, svglite (GPL-2|GPL-3) for SVG; otherwise the
# grDevices png()/svg() devices that ship with R.
#
# STATUS (2026-10-26): run on Linux with R 4.3.3 and Ubuntu 24.04's jsonlite 1.8.8,
# ggplot2 3.4.4, ragg 1.2.7 and svglite 2.1.3, both over a raw pipe and through the
# cloview launcher on Clojure/JVM and ClojureCLR (modules/bridge/test/.../rplot_test.cljc);
# through the launcher also on Windows (GitHub's windows-2025 runner) with R 4.6.1 and
# CRAN's jsonlite 2.0.0, ggplot2 4.0.3, ragg 1.5.2 and svglite 2.2.2.
# Not yet run on macOS.
#
# The rules every bridge backend follows:
#   1. answer "$/initialize" with {protocol, name, version, methods};
#   2. answer each request exactly once (result or error);
#   3. never write anything but protocol messages to stdout. Console output produced
#      while a plot is drawn is captured and copied to stderr;
#   4. exit on "$/shutdown" (after replying) and when stdin reaches EOF (the GUI is gone).
# Requests are handled one at a time, in order. A "$/cancel" can therefore only ever
# name a request that has already been answered, and is ignored; the GUI side already
# reported it as cancelled and drops the late answer.

if (!requireNamespace("jsonlite", quietly = TRUE)) {
  message("cloview R backend: the jsonlite package is required (install.packages(\"jsonlite\"))")
  quit(save = "no", status = 3)
}

PROTOCOL <- "cloview-bridge/1"
VERSION <- "0.1.0"

if (!exists("%||%", envir = baseenv())) `%||%` <- function(a, b) if (is.null(a)) b else a   # base R >= 4.4 has it

has <- function(pkg) requireNamespace(pkg, quietly = TRUE)

script_dir <- function() {
  a <- grep("^--file=", commandArgs(trailingOnly = FALSE), value = TRUE)
  if (length(a)) dirname(normalizePath(sub("^--file=", "", a[1]))) else getwd()
}

# ---------------------------------------------------------------------------
# wire

# The protocol is UTF-8 whatever locale the GUI gave this process (a GUI app on macOS,
# or a minimal container, may start it with LANG unset, i.e. the C locale). Text in plots
# wants a UTF-8 LC_CTYPE too, so switch to one when the native encoding is not UTF-8.
for (loc in c("C.UTF-8", "en_US.UTF-8", "UTF-8")) {
  if (isTRUE(l10n_info()[["UTF-8"]])) break
  invisible(suppressWarnings(Sys.setlocale("LC_CTYPE", loc)))
}

# stdin() is R's console; file("stdin") is the process's standard input. It is opened
# WITHOUT re-encoding: a connection encoding would convert to the native encoding and stop
# at the first character it cannot represent. Lines are read as bytes and marked UTF-8.
input <- file("stdin", open = "r")
output <- stdout()

read_message_line <- function() readLines(input, n = 1, warn = FALSE, encoding = "UTF-8")

send <- function(msg) {
  line <- enc2utf8(as.character(jsonlite::toJSON(msg, auto_unbox = TRUE, null = "null", na = "null", digits = NA)))
  writeLines(line, con = output, useBytes = TRUE)   # the UTF-8 bytes as they are
  flush(output)
}

result_msg <- function(id, result) list(jsonrpc = "2.0", id = id, result = result)

error_msg <- function(id, code, message, data = NULL) {
  err <- list(code = code, message = message)
  if (!is.null(data)) err$data <- data
  list(jsonrpc = "2.0", id = id, error = err)
}

# ---------------------------------------------------------------------------
# plots

PLOTS <- list()

load_plots <- function(files) {
  env <- new.env(parent = globalenv())
  # rule 3: whatever a plots file prints while it loads goes to stderr, not into the
  # first protocol message
  console <- utils::capture.output(for (f in files) sys.source(f, envir = env))
  if (length(console)) message(paste(console, collapse = "\n"))
  for (fn in ls(env, pattern = "^plot_")) PLOTS[[sub("^plot_", "", fn)]] <<- get(fn, envir = env)
}

OUT_DIR <- Sys.getenv("CLOVIEW_R_OUT", file.path(tempdir(), "cloview-r"))
dir.create(OUT_DIR, recursive = TRUE, showWarnings = FALSE)

# data arrives as columns: {"date": ["2026-10-01", ...], "kg": [71.2, ...]}
as_frame <- function(d) {
  if (is.null(d) || length(d) == 0) return(data.frame())
  cols <- lapply(d, function(col) {
    col[vapply(col, is.null, logical(1))] <- NA
    unlist(col)
  })
  as.data.frame(cols, stringsAsFactors = FALSE, check.names = FALSE)
}

open_device <- function(path, fmt, width, height, scale) {
  px_w <- round(width * scale)
  px_h <- round(height * scale)
  if (fmt == "png") {
    # res = 96 * scale: text and lines are sized as on a 96 dpi screen at any scale
    if (has("ragg")) {
      ragg::agg_png(path, width = px_w, height = px_h, units = "px", res = 96 * scale, background = "white")
    } else {
      grDevices::png(path, width = px_w, height = px_h, units = "px", res = 96 * scale,
                     type = if (isTRUE(capabilities("cairo"))) "cairo" else getOption("bitmapType"))
    }
  } else if (fmt == "svg") {
    if (has("svglite")) svglite::svglite(path, width = width / 96, height = height / 96)
    else grDevices::svg(path, width = width / 96, height = height / 96)
  } else {
    stop("format must be \"png\" or \"svg\"")
  }
  c(px_w, px_h)
}

counter <- 0L
recent <- list()   # tag stem -> files written, newest last

forget_old_files <- function(stem, path) {
  files <- c(recent[[stem]], path)
  if (length(files) > 2) {          # keep the shown image and the one before it
    unlink(files[seq_len(length(files) - 2)])
    files <- utils::tail(files, 2)
  }
  recent[[stem]] <<- files
}

is_string <- function(x) is.character(x) && length(x) == 1 && !is.na(x)
is_positive <- function(x) is.numeric(x) && length(x) == 1 && !is.na(x) && x > 0

invalid <- function(id, message) error_msg(id, -32602, message)

render <- function(id, p) {
  if (!is.list(p)) return(invalid(id, "params must be an object"))
  name <- p$plot
  if (!is_string(name)) return(invalid(id, "params.plot must be the name of a plot (a string)"))
  f <- PLOTS[[name]]
  if (is.null(f)) {
    return(error_msg(id, -32602, paste0("unknown plot '", name, "'"), list(plots = I(as.character(sort(names(PLOTS)))))))
  }
  for (k in c("width", "height", "scale")) {
    if (!is.null(p[[k]]) && !is_positive(p[[k]])) return(invalid(id, paste0("params.", k, " must be a positive number")))
  }
  if (!is.null(p$format) && !is_string(p$format)) return(invalid(id, "params.format must be \"png\" or \"svg\""))
  if (!is.null(p$tag) && !is_string(p$tag)) return(invalid(id, "params.tag must be a string"))
  width <- as.numeric(p$width %||% 640)
  height <- as.numeric(p$height %||% 400)
  scale <- as.numeric(p$scale %||% 1)
  fmt <- p$format %||% "png"
  stem <- gsub("[^A-Za-z0-9_-]", "_", sub("-[0-9]+$", "", p$tag %||% name))
  counter <<- counter + 1L
  path <- file.path(OUT_DIR, sprintf("%s-%d-%d.%s", stem, Sys.getpid(), counter, fmt))
  started <- proc.time()[["elapsed"]]
  px <- NULL
  console <- character()
  failure <- tryCatch({
    console <- utils::capture.output({
      px <- open_device(path, fmt, width, height, scale)
      tryCatch({
        plot <- f(as_frame(p$data), p$options %||% list())
        if (inherits(plot, c("ggplot", "gg", "patchwork"))) print(plot)
      }, finally = grDevices::dev.off())
      invisible(NULL)   # nothing visible, so capture.output prints nothing after dev.off()
    })
    NULL
  }, error = function(e) e)
  if (length(console)) message(paste(console, collapse = "\n"))
  if (!is.null(failure)) {
    unlink(path)
    return(error_msg(id, -32050, conditionMessage(failure), list(plot = name)))
  }
  forget_old_files(stem, path)
  result_msg(id, list(path = normalizePath(path, winslash = "/"),
                      width_px = px[1], height_px = px[2], format = fmt,
                      ms = round(1000 * (proc.time()[["elapsed"]] - started))))
}

# ---------------------------------------------------------------------------
# main loop

args <- commandArgs(trailingOnly = TRUE)
load_plots(if (length(args)) args else file.path(script_dir(), "plots.R"))

repeat {
  line <- read_message_line()
  if (length(line) == 0) break                      # EOF: the GUI is gone
  if (!nzchar(trimws(line))) next
  msg <- tryCatch(jsonlite::fromJSON(line, simplifyVector = FALSE), error = function(e) NULL)
  if (!is.list(msg) || is.null(names(msg))) {
    send(error_msg(NULL, -32700, "parse error"))
    next
  }
  method <- msg$method
  if (is.null(method)) next                         # a response: this backend sends no requests
  id <- msg$id
  if (is.null(id)) next                             # notifications ($/cancel, ...) need no answer
  params <- msg$params %||% list()
  if (identical(method, "$/initialize")) {
    send(result_msg(id, list(protocol = PROTOCOL, name = "cloview-r", version = VERSION,
                             methods = I(c("plot/render", "plot/list")),
                             r = R.version.string,
                             packages = list(ggplot2 = has("ggplot2"), ragg = has("ragg"),
                                             svglite = has("svglite")))))
  } else if (identical(method, "$/ping")) {
    send(result_msg(id, "pong"))
  } else if (identical(method, "$/shutdown")) {
    send(result_msg(id, NULL))
    break
  } else if (identical(method, "plot/list")) {
    send(result_msg(id, I(as.character(sort(names(PLOTS))))))
  } else if (identical(method, "plot/render")) {
    # rule 2 holds even for a bug here: an unexpected R error is an answer, not an exit
    send(tryCatch(render(id, params),
                  error = function(e) error_msg(id, -32603, paste("internal error:", conditionMessage(e)))))
  } else {
    send(error_msg(id, -32601, paste("unknown method", method)))
  }
}
close(input)
quit(save = "no", status = 0)
