package main

import (
	"fmt"
	"io"
	"net/http"
	"os"
	"log"
)

const BaseUploadPath="./upload"
const tpl = `<html>
<head><title>上传文件</title></head>
<body>
<form enctype="multipart/form-data" action="/upload" method="post">
 <input type="file" name="file" />
 <input type="submit" value="upload" />
</form>
</body>
</html>`

func main() {
    var err = os.MkdirAll(BaseUploadPath,os.ModePerm)
    if err==nil {
        http.HandleFunc("/", index)
    	http.HandleFunc("/upload", upload)
    	http.HandleFunc("/download", handleDownload)
    	fmt.Println("http listen at:3000")
    	http.ListenAndServe(":3000", nil)
         fmt.Println(err)
    }else{
	   fmt.Println(err)
	 }
}

func handleDownload (w http.ResponseWriter, request *http.Request) {
	if request.Method != http.MethodGet {
		w.WriteHeader(http.StatusMethodNotAllowed)
		_, _ = w.Write([]byte("Method not allowed"))
		return
	}
	filename := request.FormValue("filename")
	if filename == "" {
		w.WriteHeader(http.StatusBadRequest)
		_, _ = io.WriteString(w, "Bad request")
		return
	}
	log.Println("filename: " + filename)
	file, err := os.Open(BaseUploadPath + "/" + filename)
	if err != nil {
		w.WriteHeader(http.StatusBadRequest)
		_, _ = io.WriteString(w, "Bad request")
		return
	}
	defer file.Close()
	w.Header().Add("Content-type", "application/octet-stream")
	w.Header().Add("content-disposition", "attachment; filename=\""+filename+"\"")
	_, err = io.Copy(w, file)
	if err != nil {
		w.WriteHeader(http.StatusBadRequest)
		_, _ = io.WriteString(w, "Bad request")
		return
	}
}


func upload(w http.ResponseWriter, r *http.Request) {
	r.ParseMultipartForm(32 << 20)
	file, handler, err := r.FormFile("file")
	if err != nil {
		fmt.Println(err)
		return
	}
	defer file.Close()
	fileName := BaseUploadPath+"/"+handler.Filename
	f, err := os.OpenFile(fileName, os.O_WRONLY|os.O_CREATE, 0666)
	if err != nil {
		fmt.Println(err)
		return
	}
	defer f.Close()
	io.Copy(f, file)
	fmt.Fprintln(w, "upload ok!")
	fmt.Println("upload ",fileName)
}

func index(w http.ResponseWriter, r *http.Request) {
	w.Write([]byte(tpl))
}

