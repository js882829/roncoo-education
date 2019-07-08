package com.roncoo.education.system.service.common.req;

import java.io.Serializable;

import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 菜单信息-保存
 *
 * @author wujing
 */
@Data
@Accessors(chain = true)
public class SysMenuSaveREQ implements Serializable {

	private static final long serialVersionUID = 1L;

	/**
	 * 父ID
	 */
	@ApiModelProperty(value = "父ID")
	private Long parentId;
	/**
	 * 菜单名称
	 */
	@ApiModelProperty(value = "菜单名称")
	private String menuName;
	/**
	 * 菜单路径
	 */
	@ApiModelProperty(value = "菜单路径")
	private String menuUrl;
	/**
	 * 菜单图标
	 */
	@ApiModelProperty(value = "菜单图标")
	private String menuIcon;
	/**
	 * 目标名称
	 */
	@ApiModelProperty(value = "目标名称")
	private String targetName;
	/**
	 * 备注
	 */
	@ApiModelProperty(value = "备注")
	private String remark;
	/**
	 * 排序
	 */
	@ApiModelProperty(value = "排序")
	private Integer sort;
}