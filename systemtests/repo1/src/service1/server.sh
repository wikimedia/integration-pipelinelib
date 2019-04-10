#!/bin/bash

while true; do
cat <<-end | nc -l 0.0.0.0 7530
HTTP/1.1 200 OK\r\n\r\nfoo
end
done
