package com.cibo.evilplot.plot

import com.cibo.evilplot.geometry.{Drawable, EmptyDrawable, Extent, Group, Translate}

object Facets {

  type FacetData = Seq[Seq[Plot[_]]]

  // Divide the plotExtent evenly among subplots.
  private def computeSubplotExtent(plot: Plot[FacetData], plotExtent: Extent): Extent = {
    val rows = plot.data.size
    val cols = plot.data.map(_.size).max
    Extent(plotExtent.width / cols, plotExtent.height / rows)
  }

  // Add padding to subplots so they are all the same size and use the same axis transformation.
  private def updatePlotsForFacet(plot: Plot[FacetData], subplotExtent: Extent): FacetData = {
    Plot.padPlots(plot.data, subplotExtent, 20, 15).map { row =>
      row.map { subplot =>
        val withX = if (subplot.xfixed) subplot else subplot.setXTransform(plot.xtransform)
        if (withX.yfixed) withX else withX.setYTransform(plot.ytransform)
      }
    }
  }

  private def facetedPlotRenderer(plot: Plot[FacetData], plotExtent: Extent): Drawable = {
    // Make sure all subplots have the same size plot area.
    val innerExtent = computeSubplotExtent(plot, plotExtent)
    val paddedPlots = updatePlotsForFacet(plot, innerExtent)

    // Render the plots.
    paddedPlots.zipWithIndex.map { case (row, yIndex) =>
      val y = yIndex * innerExtent.height
      row.zipWithIndex.map { case (subplot, xIndex) =>
        val x = xIndex * innerExtent.width
        Translate(subplot.render(innerExtent), x = x, y = y)
      }.group
    }.group
  }

  private val empty: Drawable = EmptyDrawable()

  private def topComponentRenderer(
    plot: Plot[FacetData],
    subplots: FacetData,
    extent: Extent,
    innerExtent: Extent
  ): Drawable = {
    plot.components.filter(_.position == PlotComponent.Top).reverse.foldLeft(empty) { (d, c) =>
      if (c.repeated) {
        subplots.head.zipWithIndex.map { case (subplot, i) =>
          val pextent = subplot.plotExtent(innerExtent)
          val x = i * innerExtent.width + subplot.plotOffset.x + plot.plotOffset.x
          val y = d.extent.height
          Translate(c.render(subplot, pextent), x = x, y = y)
        }.group
      } else {
        val pextent = plot.plotExtent(extent)
        val x = plot.plotOffset.x
        val y = d.extent.height
        Translate(c.render(plot, pextent), x = x, y = y)
      } behind d
    }
  }

  private def bottomComponentRenderer[T](
    plot: Plot[FacetData],
    subplots: FacetData,
    extent: Extent,
    innerExtent: Extent
  ): Drawable = {
    val startY = extent.height
    plot.components.filter { c =>
      c.position == PlotComponent.Bottom
    }.reverse.foldLeft((startY, empty)) { case ((prevY, d), c) =>
      if (c.repeated) {
        val s = subplots.last.zipWithIndex.map { case (subplot, i) =>
          val pextent = subplot.plotExtent(innerExtent)
          val rendered = c.render(subplot, pextent)
          val x = i * innerExtent.width + subplot.plotOffset.x + plot.plotOffset.x
          val y = prevY - rendered.extent.height
          (y, Translate(rendered, x = x, y = y))
        }
        (s.maxBy(_._1)._1, s.map(_._2).group behind d)
      } else {
        val pextent = plot.plotExtent(extent)
        val rendered = c.render(plot, pextent)
        val x = plot.plotOffset.x
        val y = prevY - rendered.extent.height
        (y, Translate(rendered, x = x, y = y) behind d)
      }
    }._2
  }

  private def leftComponentRenderer[T](
    plot: Plot[FacetData],
    subplots: FacetData,
    extent: Extent,
    innerExtent: Extent
  ): Drawable = {
    val leftPlots = subplots.map(_.head)
    plot.components.filter(_.position == PlotComponent.Left).foldLeft(empty) { (d, c) =>
      if (c.repeated) {
        leftPlots.zipWithIndex.map { case (subplot, i) =>
          val pextent = subplot.plotExtent(innerExtent)
          val y = i * innerExtent.height + subplot.plotOffset.y + plot.plotOffset.y
          Translate(c.render(subplot, pextent), y = y)
        }.group
      } else {
        val pextent = plot.plotExtent(extent)
        val y = plot.plotOffset.y
        Translate(c.render(plot, pextent), y = y)
      } beside d
    }
  }

  private def rightComponentRenderer[T](
    plot: Plot[FacetData],
    subplots: FacetData,
    extent: Extent,
    innerExtent: Extent
  ): Drawable = {
    val rightPlots = subplots.map(_.last)
    val startX = extent.width
    plot.components.filter { c =>
      c.position == PlotComponent.Right
    }.reverse.foldLeft((startX, empty)) { case ((prevX, d), c) =>
      if (c.repeated) {
        val s = rightPlots.zipWithIndex.map { case (subplot, i) =>
          val pextent = subplot.plotExtent(innerExtent)
          val rendered = c.render(subplot, pextent)
          val x = prevX - rendered.extent.width
          val y = i * innerExtent.height + subplot.plotOffset.y + plot.plotOffset.y
          (y, Translate(rendered, x, y))
        }
        (s.maxBy(_._1)._1, s.map(_._2).group behind d)
      } else {
        val pextent = plot.plotExtent(extent)
        val rendered = c.render(plot, pextent)
        val x = prevX - rendered.extent.width
        val y = plot.plotOffset.y
        (x, Translate(rendered, x = x, y = y) behind d)
      }
    }._2
  }

  private def overlayComponentRenderer[T](
    plot: Plot[FacetData],
    subplot: Plot[T],
    subplotExtent: Extent
  ): Drawable = {
    // Overlays will be offset to the start of the plot area.
    val pextent = subplot.plotExtent(subplotExtent)
    plot.components.filter(_.position == PlotComponent.Overlay).map { a =>
      Translate(
        a.render(subplot, pextent),
        x = subplot.plotOffset.x,
        y = subplot.plotOffset.y
      )
    }.group
  }

  private def gridComponentRenderer[T](
    position: PlotComponent.Position,
    plot: Plot[FacetData],
    subplots: FacetData,
    extent: Extent,
    innerExtent: Extent
  ): Drawable = {
    plot.components.filter(_.position == position).map { c =>
      if (c.repeated) {
        subplots.zipWithIndex.flatMap { case (row, yIndex) =>
          row.zipWithIndex.map { case (subplot, xIndex) =>
            val pextent = subplot.plotExtent(innerExtent)
            val x = xIndex * innerExtent.width + subplot.plotOffset.x
            val y = yIndex * innerExtent.height + subplot.plotOffset.y
            Translate(c.render(subplot, pextent), x = x, y = y)
          }
        }.group
      } else {
        val pextent = plot.plotExtent(extent)
        c.render(plot, pextent)
      }
    }.group
  }

  private def facetedComponentRenderer(plot: Plot[FacetData], extent: Extent): (Drawable, Drawable) = {
    val plotExtent = plot.plotExtent(extent)
    val innerExtent = computeSubplotExtent(plot, plotExtent)
    val paddedPlots = updatePlotsForFacet(plot, innerExtent)

    val top = topComponentRenderer(plot, paddedPlots, extent, innerExtent)
    val bottom = bottomComponentRenderer(plot, paddedPlots, extent, innerExtent)
    val left = leftComponentRenderer(plot, paddedPlots, extent, innerExtent)
    val right = rightComponentRenderer(plot, paddedPlots, extent, innerExtent)
    val overlay = gridComponentRenderer(PlotComponent.Overlay, plot, paddedPlots, extent, innerExtent)
    val background = gridComponentRenderer(PlotComponent.Background, plot, paddedPlots, extent, innerExtent)

    (Group(Seq(top, bottom, left, right, overlay)), background)
  }

  def apply(plots: Seq[Seq[Plot[_]]]): Plot[FacetData] = {

    // X bounds for each column.
    val columnXBounds = plots.transpose.map(col => Plot.combineBounds(col.map(_.xbounds)))

    // Y bounds for each row.
    val rowYBounds = plots.map(row => Plot.combineBounds(row.map(_.ybounds)))

    // Update bounds on subplots for subplots that don't already have axes.
    val updatedPlots = plots.zipWithIndex.map { case (row, y) =>
      row.zipWithIndex.map { case (subplot, x) =>
        (subplot.xfixed, subplot.yfixed) match {
          case (true, true)   => subplot
          case (true, false)  => subplot.ybounds(rowYBounds(y))
          case (false, true)  => subplot.xbounds(columnXBounds(x))
          case (false, false) => subplot.xbounds(columnXBounds(x)).ybounds(rowYBounds(y))
        }
      }
    }

    Plot[FacetData](
      data = updatedPlots,
      xbounds = Plot.combineBounds(columnXBounds),
      ybounds = Plot.combineBounds(rowYBounds),
      renderer = facetedPlotRenderer,
      componentRenderer = facetedComponentRenderer
    )
  }
}

