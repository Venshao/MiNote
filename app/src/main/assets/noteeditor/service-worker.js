self.addEventListener("message", function (event) {
  console.log(event.data); // 接收到的消息
  // 假设event.data是一个对象，包含了要执行任务的相关信息
  if (event.data.command == "runLongTask") {
    // 执行长时间运行的任务...
    const result = runLongTask(event.data.params);
    // 任务完成后，向主线程发送结果
    event.ports[0].postMessage(result);
  }
});

function runLongTask(params) {
  if (window.setData && params && params instanceof Array) {
    window.setData(...params);
  }
  // 这里实现您的长时间运行的任务
  return "任务结果";
}

// self.addEventListener("fetch", event => {
//   console.log("fetch something");
//   event.respondWith(
//     caches.match(event.request).then(response => {
//       if (response) {
//         return response; // Return the cached response if available
//       }

//       console.log("没有相关缓存", event.request);
//       // If the image is not in the cache, fetch it from the network
//       return (
//         fetch(event.request)
//           // return fetch(event.request.url, { mode: "cors", method: "GET" })
//           .then(networkResponse => {
//             // Clone the response to cache it
//             const clonedResponse = networkResponse.clone();

//             if (event.request) {
//               // https://com.miui.notes/note_image
//               console.log("event.request", event.request);
//               // Open the cache and add the image to it
//               caches.open("image-cache").then(cache => {
//                 cache.put(event.request, clonedResponse);
//               });
//             }
//             // Return the network response to display the image
//             return networkResponse;
//           })
//           .catch(error => {
//             console.log("fetch 失败：" + error);
//           })
//       );
//     }),
//   );
// });
