package com.kreativekoala.surfsense

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.*

sealed class NotificationItem {
    data class Notification(val data: NotificationData) : NotificationItem()
    data class Result(val data: ResultData) : NotificationItem()
}

class NotificationsAdapter(
    private var items: List<NotificationItem> = emptyList(),
    private val recyclerView: RecyclerView? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_NOTIFICATION = 1
        private const val TYPE_RESULT = 2
    }

    fun updateData(newItems: List<NotificationItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    fun addItem(item: NotificationItem) {
        items = listOf(item) + items
        notifyItemInserted(0)
        recyclerView?.scrollToPosition(0)
    }

    fun clearItems() {
        items = emptyList()
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is NotificationItem.Notification -> TYPE_NOTIFICATION
            is NotificationItem.Result -> TYPE_RESULT
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            TYPE_NOTIFICATION -> NotificationViewHolder(
                LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_notification, parent, false)
            )
            TYPE_RESULT -> ResultViewHolder(
                LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_result, parent, false)
            )
            else -> throw IllegalArgumentException("Invalid view type: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is NotificationItem.Notification -> (holder as NotificationViewHolder).bind(item.data)
            is NotificationItem.Result -> (holder as ResultViewHolder).bind(item.data)
        }
    }

    override fun getItemCount() = items.size

    class NotificationViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val title: TextView = view.findViewById(R.id.notification_title)
        private val text: TextView = view.findViewById(R.id.notification_text)
        private val time: TextView = view.findViewById(R.id.notification_time)

        fun bind(notification: NotificationData) {
            title.text = notification.title
            text.text = notification.text
            time.text = SimpleDateFormat("hh:mm a", Locale.getDefault())
                .format(Date(notification.timestamp))
        }
    }

    class ResultViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val title: TextView = view.findViewById(R.id.result_title)
        private val url: TextView = view.findViewById(R.id.result_url)
        private val query: TextView = view.findViewById(R.id.result_query)
        private val result: TextView = view.findViewById(R.id.result_text)
        private val time: TextView = view.findViewById(R.id.result_time)

        fun bind(resultData: ResultData) {
            title.text = "Tracking Result: ${resultData.userQuery}"
            url.text = "URL: ${resultData.url}"
            query.text = "Query: ${resultData.userQuery}"
            result.text = resultData.result
            time.text = SimpleDateFormat("hh:mm a", Locale.getDefault())
                .format(Date()) // Use actual timestamp if available
        }
    }
}