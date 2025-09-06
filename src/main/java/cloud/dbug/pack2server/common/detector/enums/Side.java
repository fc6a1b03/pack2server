package cloud.dbug.pack2server.common.detector.enums;

/**
 * 检测结果
 * @author 拒绝者
 * @date 2025-09-06
 */
public enum Side {
    /**
     * 无
     */
    NONE,
    /**
     * 客户端
     */
    CLIENT,
    /**
     * 服务器
     */
    SERVER,
    /**
     * 两者
     */
    BOTH;

    /**
     * 是服务
     * @return {@link Boolean }
     */
    public Boolean isServer() {
        return Side.SERVER.equals(this) || Side.BOTH.equals(this) || !Side.NONE.equals(this);
    }

    /**
     * 是客户
     * @return {@link Boolean }
     */
    public Boolean isClient() {
        return Side.CLIENT.equals(this) || Side.BOTH.equals(this);
    }
}