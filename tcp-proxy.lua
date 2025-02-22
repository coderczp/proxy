local uv = require("uv")

-- Create listening socket and bind to 127.0.0.1:8080
local server = uv.new_tcp()
local host = "0.0.0.0"
local port = 8811
local upsHost = "127.0.0.1"
local upsPort = 80
server:bind(host, port)

-- Setup listener
server:listen(128, function(error)
    -- This function is executed for each new client
    print("New connection")

    -- Create handles for client and upstream
    local client = uv.new_tcp()
    local upstream = uv.new_tcp()

    -- Accept the client connection
    server:accept(client)

    -- Connect to upstream server
    upstream:connect("127.0.0.1", 80, function(error)
        if error then
            print('Failed to connect to upstream: ' .. error)
            -- If can't connect to upstream, cleanup both handles
            upstream:close()
            client:close()
        else
            -- Setup handler to send data from upstream to client
            upstream:read_start(function(err, data)
                p('server resp:', data)
                if err then
                    print("Upstream error:" .. err)
                end
                if data then
                    print("Upstream response: " .. data)
                    client:write(data)
                else
                    -- Upstream disconnected, cleanup handles
                    upstream:close()
                    client:close()
                    print("Upstream disconnected")
                end
            end)

            -- Setup handler to send data from client to upstream
            client:read_start(function(err, data)
                p('client send:', data)
                if err then
                    print("Client error:" .. err)
                end
                if data then
                    print("Client request: " .. data)
                    upstream:write(data)
                else
                    -- Client disconnected, cleanup handles
                    upstream:close()
                    client:close()
                    print("Client disconnected")
                end
            end)
        end
    end)
end)

print("Listening on:", host, port, "proxying to", upsHost, upsPort)
