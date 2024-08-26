package net.consolator

import android.os.*
import android.view.*
import android.view.GestureDetector.*
import iso.consolator.*

@Tag(OVERLAY_FRAGMENT)
open class OverlayFragment(
    previous: MainFragment? = null,
    override var interceptor: InterceptFunction? = null
) : MainFragment(), Interceptor, OnContextClickListener {
    init {
        previous?.view?.apply {
        /* save previous view */ } }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?) =
        if (savedInstanceState === null)
            inflater.inflate(contentLayoutId, container).apply {
            /* rebind to previous view */ }
        else
            super.onCreateView(inflater, container, savedInstanceState)

    override fun onContextClick(event: MotionEvent) =
        intercept(OnContextClickListener::onContextClick, event) {
        /* process event internal to overlay view */
        true }
}