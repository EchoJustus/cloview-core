# Example plots for the cloview R backend (backend.R loads every function named
# plot_<name> from this file). A plot function gets the request's data as a data frame
# and its options as a list, and draws on the device backend.R has opened: return a
# ggplot (backend.R prints it) or draw with base graphics.
#
#   {:r/plot {:id :weight-trend :plot "weight_trend"
#             :data {:date ["2026-10-01" "2026-10-08"] :kg [71.2 70.8]}
#             :options {:colour "#3367D6"}}}

plot_weight_trend <- function(data, options) {
  data$date <- as.Date(data$date)
  colour <- options$colour %||% "#3367D6"
  if (requireNamespace("ggplot2", quietly = TRUE)) {
    ggplot2::ggplot(data, ggplot2::aes(x = date, y = kg)) +
      ggplot2::geom_line(colour = colour, linewidth = 0.8) +
      ggplot2::geom_point(colour = colour, size = 1.6) +
      ggplot2::labs(x = NULL, y = "kg") +
      ggplot2::theme_minimal(base_size = 12)
  } else {
    graphics::plot(data$date, data$kg, type = "o", col = colour, pch = 16, xlab = "", ylab = "kg")
  }
}

plot_histogram <- function(data, options) {
  values <- data[[options$column %||% names(data)[1]]]
  if (requireNamespace("ggplot2", quietly = TRUE)) {
    ggplot2::ggplot(data.frame(v = values), ggplot2::aes(x = v)) +
      ggplot2::geom_histogram(bins = options$bins %||% 20, fill = "#3367D6", colour = "white") +
      ggplot2::labs(x = options$label %||% NULL, y = NULL) +
      ggplot2::theme_minimal(base_size = 12)
  } else {
    graphics::hist(values, breaks = options$bins %||% 20, col = "#3367D6", border = "white",
                   main = "", xlab = options$label %||% "")
  }
}
